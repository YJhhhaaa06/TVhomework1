package com.itheima.follow.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.follow.model.dto.InboxRebuildMessage;
import com.itheima.mq.MqDeliveryDispatcher;
import com.itheima.mq.MqMessage;
import com.itheima.mq.MqPublisher;
import com.itheima.mq.MqTopology;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InboxRebuildNotifier} 单测（feed1-19 T19；feed3-T31 起异步化）：载荷与路由键 / 三层降级
 * （未确认、序列化失败、意外异常）/ 异步解耦留证。
 *
 * <p>隔离手法：mock {@link MqPublisher}（不依赖 broker），{@link JacksonCodec} 默认用真实件覆盖
 * record 的 JSON 往返；异常分支注入 stub codec。异步化后经真实 {@link MqDeliveryDispatcher}
 * （小容量受控构造）投递，断言一律先等任务在 worker 上完成（{@code verify(timeout)} / 日志轮询），
 * {@code tearDown} 的 {@code destroy()} 有界 drain 清场，防跨用例串扰。
 */
class InboxRebuildNotifierTest {

    private static final long USER = 7L;

    private MqPublisher publisher;
    private MqDeliveryDispatcher dispatcher;
    /** 去抖窗口 0 = 显式关闭（feed3-T33-A）：既有用例保持"事件即投递"语义，不受窗口影响。 */
    private InboxRebuildDebouncer debouncer;
    private InboxRebuildNotifier notifier;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        publisher = mock(MqPublisher.class);
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        debouncer = new InboxRebuildDebouncer(0L);
        notifier = new InboxRebuildNotifier(publisher, new JacksonCodec(), dispatcher, debouncer);
        probe = LogProbe.attachTo(LogUtil.getLogger(InboxRebuildNotifier.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
        debouncer.destroy();
        dispatcher.destroy();
    }

    @Test
    void publishesJsonPayloadToRebuildExchangeAndInboxRoutingKey() {
        when(publisher.publish(any())).thenReturn(true);

        notifier.publishInboxRebuild(USER);

        ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
        verify(publisher, timeout(2000)).publish(captor.capture());
        MqMessage message = captor.getValue();
        assertEquals(MqTopology.EXCHANGE_REBUILD, message.exchange());
        assertEquals(MqTopology.RK_REBUILD_INBOX, message.routingKey());
        // 载荷 JSON 往返等价（不锁字面量格式，抗字段序变化）
        InboxRebuildMessage payload = new JacksonCodec().fromJson(
                new String(message.body(), StandardCharsets.UTF_8), InboxRebuildMessage.class);
        assertEquals(new InboxRebuildMessage(USER), payload);
        assertTrue(probe.records().isEmpty(), "投递成功不记日志（避免每次关注/取关刷一行）");
    }

    @Test
    void publishFailureIsSwallowedWithoutWarning() {
        when(publisher.publish(any())).thenReturn(false);

        assertDoesNotThrow(() -> notifier.publishInboxRebuild(USER));

        verify(publisher, timeout(2000)).publish(any());
        assertTrue(probe.records().isEmpty(),
                "沿用 MqPublisher 口径：未确认 / 不可用不刷 WARNING（只留默认不输出的 FINE）");
    }

    @Test
    void serializationFailureSkipsPublishWithStackedWarning() {
        JacksonCodec broken = mock(JacksonCodec.class);
        when(broken.toJson(any())).thenThrow(new CacheException("serialize failed"));
        InboxRebuildNotifier degraded = new InboxRebuildNotifier(publisher, broken, dispatcher, debouncer);

        assertDoesNotThrow(() -> degraded.publishInboxRebuild(USER));

        List<LogRecord> warnings = awaitLogs(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("载荷序列化失败"));
        assertNotNull(warnings.getFirst().getThrown(), "序列化失败是该链唯一捕获点 → 必须持栈");
        verify(publisher, never()).publish(any());
    }

    @Test
    void nullJsonSkipsPublishWithWarning() {
        JacksonCodec nullCodec = mock(JacksonCodec.class);
        when(nullCodec.toJson(any())).thenReturn(null);
        InboxRebuildNotifier degraded = new InboxRebuildNotifier(publisher, nullCodec, dispatcher, debouncer);

        assertDoesNotThrow(() -> degraded.publishInboxRebuild(USER));

        awaitLogs(Level.WARNING);
        verify(publisher, never()).publish(any());
        assertEquals(1, probe.atLevel(Level.WARNING).size());
    }

    @Test
    void unexpectedRuntimeFailureIsSwallowedWithSevereStack() {
        // 契约"绝不抛"的最后兜底：模拟下游越界抛异常（例如 MqPublisher"不抛"契约被破坏）
        when(publisher.publish(any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> notifier.publishInboxRebuild(USER));

        List<LogRecord> severes = awaitLogs(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建投递异常"));
        assertNotNull(severes.getFirst().getThrown(), "需人介入 → SEVERE + 栈");
    }

    /** T31 验收留证（关注/取关链）：publish 慢确认（模拟 waitForConfirms）不拖住关注线程 RT。 */
    @Test
    void submitReturnsBeforeConfirmCompletes() throws Exception {
        CountDownLatch confirmed = new CountDownLatch(1);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            Thread.sleep(300); // 模拟 waitForConfirms 慢确认
            confirmed.countDown();
            return true;
        });

        long t0 = System.nanoTime();
        notifier.publishInboxRebuild(USER);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 200, "关注/取关线程不应等 confirm，实测 " + elapsedMs + "ms");
        assertTrue(confirmed.await(2, TimeUnit.SECONDS), "投递应最终在 worker 上完成");
        verify(publisher).publish(any());
    }

    /** T33-A 接入留证：同一用户的连点 / 批量关注在窗口内**只投一条**重建消息（经去抖器 + dispatcher 全链）。 */
    @Test
    void burstOfRebuildRequestsForSameUserIsDebouncedToOneDelivery() throws Exception {
        InboxRebuildDebouncer windowed = new InboxRebuildDebouncer(150L);
        try {
            when(publisher.publish(any())).thenReturn(true);
            InboxRebuildNotifier debounced =
                    new InboxRebuildNotifier(publisher, new JacksonCodec(), dispatcher, windowed);

            debounced.publishInboxRebuild(USER);
            debounced.publishInboxRebuild(USER);
            debounced.publishInboxRebuild(USER);

            verify(publisher, timeout(2000)).publish(any());
            Thread.sleep(300L);   // 再等两个窗口：确认只投了一条（前两次的排期已被取消）
            verify(publisher, times(1)).publish(any());
        } finally {
            windowed.destroy();
        }
    }

    /** 轮询等待 worker 侧日志落地（异步化后日志在 mq-delivery 线程写入，与断言存在时序差）。 */
    private List<LogRecord> awaitLogs(Level level) {
        long deadline = System.currentTimeMillis() + 2000;
        List<LogRecord> hits = probe.atLevel(level);
        while (hits.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            hits = probe.atLevel(level);
        }
        return hits;
    }
}
