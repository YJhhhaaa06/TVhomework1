package com.itheima.follow.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.follow.model.dto.InboxRebuildMessage;
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
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InboxRebuildNotifier} 单测（feed1-19 T19）：载荷与路由键 / 三层降级（未确认、序列化失败、意外异常）。
 *
 * <p>隔离手法：mock {@link MqPublisher}（不依赖 broker），{@link JacksonCodec} 默认用真实件覆盖
 * record 的 JSON 往返；异常分支注入 stub codec。
 */
class InboxRebuildNotifierTest {

    private static final long USER = 7L;

    private MqPublisher publisher;
    private InboxRebuildNotifier notifier;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        publisher = mock(MqPublisher.class);
        notifier = new InboxRebuildNotifier(publisher, new JacksonCodec());
        probe = LogProbe.attachTo(LogUtil.getLogger(InboxRebuildNotifier.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    @Test
    void publishesJsonPayloadToRebuildExchangeAndInboxRoutingKey() {
        when(publisher.publish(any())).thenReturn(true);

        notifier.publishInboxRebuild(USER);

        ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
        verify(publisher).publish(captor.capture());
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

        assertTrue(probe.records().isEmpty(),
                "沿用 MqPublisher 口径：未确认 / 不可用不刷 WARNING（只留默认不输出的 FINE）");
    }

    @Test
    void serializationFailureSkipsPublishWithStackedWarning() {
        JacksonCodec broken = mock(JacksonCodec.class);
        when(broken.toJson(any())).thenThrow(new CacheException("serialize failed"));
        InboxRebuildNotifier degraded = new InboxRebuildNotifier(publisher, broken);

        assertDoesNotThrow(() -> degraded.publishInboxRebuild(USER));

        verify(publisher, never()).publish(any());
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("载荷序列化失败"));
        assertNotNull(warnings.getFirst().getThrown(), "序列化失败是该链唯一捕获点 → 必须持栈");
    }

    @Test
    void nullJsonSkipsPublishWithWarning() {
        JacksonCodec nullCodec = mock(JacksonCodec.class);
        when(nullCodec.toJson(any())).thenReturn(null);
        InboxRebuildNotifier degraded = new InboxRebuildNotifier(publisher, nullCodec);

        assertDoesNotThrow(() -> degraded.publishInboxRebuild(USER));

        verify(publisher, never()).publish(any());
        assertEquals(1, probe.atLevel(Level.WARNING).size());
    }

    @Test
    void unexpectedRuntimeFailureIsSwallowedWithSevereStack() {
        // 契约"绝不抛"的最后兜底：模拟下游越界抛异常（例如 MqPublisher"不抛"契约被破坏）
        when(publisher.publish(any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> notifier.publishInboxRebuild(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建投递异常"));
        assertNotNull(severes.getFirst().getThrown(), "需人介入 → SEVERE + 栈");
    }
}
