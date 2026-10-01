package com.itheima.mq;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqDeliveryBuffer} 单测（feed3-T33-B）：不可用 ⇒ 暂存不触 broker / 可用 ⇒ 直投不暂存 /
 * 未确认 ⇒ 不入缓冲（既有降级口径）/ 恢复后重放（探测通道）/ 溢出丢弃 + **节流告警** /
 * 关停丢弃并停收 / 非正容量 fail-fast。
 *
 * <p>隔离手法：mock {@link MqConnectionProvider} 与 {@link MqPublisher}（不依赖真实 broker）；
 * 重放路径由包级 {@link MqDeliveryBuffer#probe()} 直接驱动（不依赖 30s 计时）。受控容量 = 4。
 */
class MqDeliveryBufferTest {

    private static final int CAPACITY = 4;

    private MqConnectionProvider provider;
    private MqPublisher publisher;
    private MqDeliveryBuffer buffer;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        provider = mock(MqConnectionProvider.class);
        publisher = mock(MqPublisher.class);
        buffer = new MqDeliveryBuffer(provider, publisher, CAPACITY);
        probe = LogProbe.attachTo(LogUtil.getLogger(MqDeliveryBuffer.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
        buffer.destroy();
    }

    @Test
    void unavailableConnectionBuffersWithoutTouchingBroker() {
        when(provider.ensureConnected()).thenReturn(false);

        assertFalse(buffer.publish(message("feed.push.content")), "不可用时投递返回 false（降级语义不变）");
        assertEquals(1, buffer.pendingCount(), "消息应暂存待补偿");
        verify(publisher, never()).publish(any());
    }

    @Test
    void availableConnectionDeliversDirectlyWithoutBuffering() {
        when(provider.ensureConnected()).thenReturn(true);
        when(publisher.publish(any())).thenReturn(true);

        assertTrue(buffer.publish(message("feed.push.content")));
        assertEquals(0, buffer.pendingCount(), "可用时不经缓冲");
        verify(publisher).publish(any());
    }

    /** 拍板口径：确认超时 / nack（已投出但状态不明）**不入缓冲**——重放可能双投，保持既有降级。 */
    @Test
    void unconfirmedDeliveryIsNotBuffered() {
        when(provider.ensureConnected()).thenReturn(true);
        when(publisher.publish(any())).thenReturn(false);

        assertFalse(buffer.publish(message("feed.push.content")));
        assertEquals(0, buffer.pendingCount(), "未确认不入缓冲（既有降级口径不扩范围）");
    }

    @Test
    void probeReplaysBufferedMessagesOnceConnectionRecovers() {
        when(provider.ensureConnected()).thenReturn(false);
        assertFalse(buffer.publish(message("feed.push.content")));
        assertFalse(buffer.publish(message("feed.push.backfill")));
        assertEquals(2, buffer.pendingCount());

        // 连接恢复：探测通道（主通道）重放
        when(provider.isAvailable()).thenReturn(true);
        when(provider.ensureConnected()).thenReturn(true);
        when(publisher.publish(any())).thenReturn(true);

        buffer.probe();

        assertEquals(0, buffer.pendingCount(), "恢复后应全部重放");
        verify(publisher, times(2)).publish(any());
        List<LogRecord> infos = probe.atLevel(Level.INFO);
        assertEquals(1, infos.size(), () -> "重放应恰一条 INFO 结论行，实际: " + probe.records());
        assertTrue(infos.getFirst().getMessage().contains("补偿重放完成"),
                () -> "结论行文案: " + infos.getFirst().getMessage());
    }

    @Test
    void bufferStaysWhenConnectionStillUnavailableOnProbe() {
        when(provider.ensureConnected()).thenReturn(false);
        buffer.publish(rebuildMessage());

        buffer.probe();   // 仍未恢复：探测不应清空缓冲

        assertEquals(1, buffer.pendingCount(), "连接仍不可用 ⇒ 保留剩余待下次探测");
        verify(publisher, never()).publish(any());
    }

    /** flush 重放中"单条未确认"⇒ 放回队尾并结束本轮（剩余保留、不丢消息；重放 = 至少一次语义）。 */
    @Test
    void unconfirmedDuringReplayIsRequeuedAndKeptForNextProbe() {
        when(provider.ensureConnected()).thenReturn(false);
        buffer.publish(message("feed.push.content"));
        buffer.publish(message("feed.push.content"));
        assertEquals(2, buffer.pendingCount());

        // 连接恢复，但 broker 未确认（nack / 超时）⇒ 放回队尾、本轮结束
        when(provider.isAvailable()).thenReturn(true);
        when(provider.ensureConnected()).thenReturn(true);
        when(publisher.publish(any())).thenReturn(false);

        buffer.probe();

        assertEquals(2, buffer.pendingCount(), "未确认的消息应放回队尾保留（重放 = 至少一次）");
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void overflowDropsNewestWithThrottledWarning() {
        when(provider.ensureConnected()).thenReturn(false);

        for (int i = 0; i < CAPACITY + 3; i++) {
            buffer.publish(message("feed.push.content"));
        }

        assertEquals(CAPACITY, buffer.pendingCount(), "缓冲有界：超出容量的消息被丢弃");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "溢出告警须节流（首条一条，不按条刷），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("补偿缓冲已满"));
    }

    @Test
    void destroyDropsPendingAndStopsAccepting() {
        when(provider.ensureConnected()).thenReturn(false);
        buffer.publish(message("feed.push.content"));
        buffer.publish(rebuildMessage());

        buffer.destroy();

        assertEquals(0, buffer.pendingCount(), "关停即丢弃（残余②口径）");
        assertFalse(buffer.publish(message("feed.push.content")), "关停后不再接收");
        assertEquals(0, buffer.pendingCount());
        assertTrue(probe.atLevel(Level.INFO).stream()
                        .anyMatch(r -> r.getMessage().contains("关停")),
                () -> "关停应留一条汇总 INFO，实际: " + probe.records());
    }

    @Test
    void nonPositiveCapacityRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new MqDeliveryBuffer(provider, publisher, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MqDeliveryBuffer(provider, publisher, -1));
    }

    private static MqMessage message(String routingKey) {
        return MqMessage.push(routingKey, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static MqMessage rebuildMessage() {
        return MqMessage.rebuild(MqTopology.RK_REBUILD_INBOX, "{}".getBytes(StandardCharsets.UTF_8));
    }
}
