package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.feed.model.dto.FeedPushMessage;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedPushNotifier} 单测（feed1-18 T18；feed3-T31 起异步化）：载荷与路由键 / 三层降级
 * （未确认、序列化失败、意外异常）/ 异步解耦留证。
 *
 * <p>隔离手法：mock {@link MqPublisher}（不依赖 broker），{@link JacksonCodec} 默认用真实件覆盖
 * record 的 JSON 往返；异常分支注入 stub codec。异步化后经真实 {@link MqDeliveryDispatcher}
 * （小容量受控构造）投递，断言一律先等任务在 worker 上完成（{@code verify(timeout)} / 日志轮询），
 * {@code tearDown} 的 {@code destroy()} 有界 drain 清场，防跨用例串扰。
 */
class FeedPushNotifierTest {

    private static final long CONTENT = 42L;
    private static final long AUTHOR = 9L;

    private MqPublisher publisher;
    private MqDeliveryDispatcher dispatcher;
    private FeedPushNotifier notifier;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        publisher = mock(MqPublisher.class);
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        notifier = new FeedPushNotifier(publisher, new JacksonCodec(), dispatcher);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedPushNotifier.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
        dispatcher.destroy();
    }

    @Test
    void publishesJsonPayloadToPushExchangeAndContentRoutingKey() {
        when(publisher.publish(any())).thenReturn(true);

        notifier.publishContentPublished(CONTENT, AUTHOR);

        ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
        verify(publisher, timeout(2000)).publish(captor.capture());
        MqMessage message = captor.getValue();
        assertEquals(MqTopology.EXCHANGE_PUSH, message.exchange());
        assertEquals(MqTopology.RK_PUSH_CONTENT, message.routingKey());
        // 载荷 JSON 往返等价（不锁字面量格式，抗字段序变化）
        FeedPushMessage payload = new JacksonCodec().fromJson(
                new String(message.body(), StandardCharsets.UTF_8), FeedPushMessage.class);
        assertEquals(new FeedPushMessage(CONTENT, AUTHOR), payload);
        assertTrue(probe.records().isEmpty(), "投递成功不记日志（避免每发布刷一行）");
    }

    @Test
    void publishFailureIsSwallowedWithoutWarning() {
        when(publisher.publish(any())).thenReturn(false);

        assertDoesNotThrow(() -> notifier.publishContentPublished(CONTENT, AUTHOR));

        verify(publisher, timeout(2000)).publish(any());
        assertTrue(probe.records().isEmpty(),
                "沿用 MqPublisher 口径：未确认 / 不可用不刷 WARNING（只留默认不输出的 FINE）");
    }

    @Test
    void serializationFailureSkipsPublishWithStackedWarning() {
        JacksonCodec broken = mock(JacksonCodec.class);
        when(broken.toJson(any())).thenThrow(new CacheException("serialize failed"));
        FeedPushNotifier degraded = new FeedPushNotifier(publisher, broken, dispatcher);

        assertDoesNotThrow(() -> degraded.publishContentPublished(CONTENT, AUTHOR));

        List<LogRecord> warnings = awaitLogs(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("载荷序列化失败"));
        assertNotNull(warnings.getFirst().getThrown(), "序列化失败是该链唯一捕获点 → 必须持栈");
        verify(publisher, never()).publish(any());
    }

    @Test
    void nullJsonSkipsPublish() {
        JacksonCodec nullCodec = mock(JacksonCodec.class);
        when(nullCodec.toJson(any())).thenReturn(null);
        FeedPushNotifier degraded = new FeedPushNotifier(publisher, nullCodec, dispatcher);

        assertDoesNotThrow(() -> degraded.publishContentPublished(CONTENT, AUTHOR));

        awaitLogs(Level.WARNING);
        verify(publisher, never()).publish(any());
        assertEquals(1, probe.atLevel(Level.WARNING).size());
    }

    @Test
    void unexpectedRuntimeFailureIsSwallowedWithSevereStack() {
        // 契约"绝不抛"的最后兜底：模拟下游越界抛异常（例如 MqPublisher"不抛"契约被破坏）
        when(publisher.publish(any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> notifier.publishContentPublished(CONTENT, AUTHOR));

        List<LogRecord> severes = awaitLogs(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("写扩散投递异常"));
        assertNotNull(severes.getFirst().getThrown(), "需人介入 → SEVERE + 栈");
    }

    /** T31 验收留证（发布链）：publish 慢确认（模拟 waitForConfirms）不拖住发布线程 RT。 */
    @Test
    void submitReturnsBeforeConfirmCompletes() throws Exception {
        CountDownLatch confirmed = new CountDownLatch(1);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            Thread.sleep(300); // 模拟 waitForConfirms 慢确认
            confirmed.countDown();
            return true;
        });

        long t0 = System.nanoTime();
        notifier.publishContentPublished(CONTENT, AUTHOR);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 200, "发布线程不应等 confirm，实测 " + elapsedMs + "ms");
        assertTrue(confirmed.await(2, TimeUnit.SECONDS), "投递应最终在 worker 上完成");
        verify(publisher).publish(any());
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
