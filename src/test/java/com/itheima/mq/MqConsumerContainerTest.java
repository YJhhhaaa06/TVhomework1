package com.itheima.mq;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqConsumerContainer} 单测（T17 feed1-17；**feed3-T32 改写失败出口**）：
 * 启动时序 / 参数校验 / 关停；失败治理用"处理器调用计数 + ack / nack + 日志级别"锁定——
 * ① 重试自愈（首试失败、重试成功 ⇒ ack + 一条 WARNING 结论行）；
 * ② 重试耗尽（有界次数 ⇒ basicNack(tag, false, false) + 唯一一条持栈 SEVERE）；
 * ③ 关停中断（不 ack 不 nack、消息随连接关闭回主队列）；④ 0 = 显式关闭重试。
 *
 * <p>重试参数一律用"受控构造"注入（1ms 退避 / 大退避按需），避免默认 1s 退避拖慢失败用例；
 * FINE 诊断行不断言（logger 有效级别为 INFO，探针只收已通过级别检查的记录——见 {@link LogProbe}）。
 */
class MqConsumerContainerTest {

    private static final long DELIVERY_TAG = 7L;

    private static final String CONSUMER_TAG = "ctag";

    /** 默认测试重试参数：1 次重试 + 1ms 退避（快）。 */
    private static final int FAST_RETRIES = 1;
    private static final long FAST_BACKOFF_MILLIS = 1L;

    /** 大退避：若代码误入 sleep 路径会显著拖慢（用于"中断 / 0 重试"用例，保证不真等待）。 */
    private static final long HUGE_BACKOFF_MILLIS = 60_000L;

    private MqConnectionProvider provider;
    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        provider = mock(MqConnectionProvider.class);
        channel = mock(Channel.class);
        // broker 返回的消费者标识（destroy 时按它取消消费者）
        when(channel.basicConsume(anyString(), anyBoolean(),
                any(DeliverCallback.class), any(CancelCallback.class))).thenReturn(CONSUMER_TAG);
        when(provider.newConsumerChannel()).thenReturn(channel);
    }

    /** 连接先建立、消费者后注册（"可用即启动"路径）。 */
    private MqConsumerContainer connectedContainer() {
        return connectedContainer(FAST_RETRIES, FAST_BACKOFF_MILLIS);
    }

    private MqConsumerContainer connectedContainer(int maxRetries, long backoffMillis) {
        when(provider.isAvailable()).thenReturn(true);
        MqConsumerContainer container = new MqConsumerContainer(provider, maxRetries, backoffMillis);
        container.init();
        return container;
    }

    /** 消费者先注册、连接后建立（"连接回调启动"路径）。 */
    private MqConsumerContainer waitingContainer() {
        when(provider.isAvailable()).thenReturn(false);
        MqConsumerContainer container = new MqConsumerContainer(provider, FAST_RETRIES, FAST_BACKOFF_MILLIS);
        container.init();
        return container;
    }

    private MqConnectionListener capturedListener() {
        ArgumentCaptor<MqConnectionListener> captor = ArgumentCaptor.forClass(MqConnectionListener.class);
        verify(provider).addConnectionListener(captor.capture());
        return captor.getValue();
    }

    private DeliverCallback capturedDeliverCallback(Channel target) throws IOException {
        ArgumentCaptor<DeliverCallback> captor = ArgumentCaptor.forClass(DeliverCallback.class);
        verify(target).basicConsume(anyString(), anyBoolean(), captor.capture(), any(CancelCallback.class));
        return captor.getValue();
    }

    private static Delivery delivery(String routingKey) {
        return new Delivery(
                new Envelope(DELIVERY_TAG, false, MqTopology.EXCHANGE_PUSH, routingKey), null, new byte[]{9});
    }

    // ==================== 启动时机（消除 IoC 顺序依赖） ====================

    @Test
    void startsConsumingImmediatelyWhenConnectionAlreadyAvailable() throws Exception {
        MqConsumerContainer container = connectedContainer();

        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });

        verify(channel).basicQos(1);
        verify(channel).basicConsume(eq(MqTopology.QUEUE_PUSH), eq(false),
                any(DeliverCallback.class), any(CancelCallback.class));
    }

    @Test
    void startsConsumingOnConnectedCallbackWhenRegisteredBeforeConnect() throws Exception {
        MqConsumerContainer container = waitingContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });

        verify(channel, never()).basicConsume(anyString(), anyBoolean(),
                any(DeliverCallback.class), any(CancelCallback.class));

        capturedListener().onConnected();

        verify(channel).basicConsume(eq(MqTopology.QUEUE_PUSH), eq(false),
                any(DeliverCallback.class), any(CancelCallback.class));
    }

    @Test
    void usesRegisteredPrefetch() throws Exception {
        MqConsumerContainer container = connectedContainer();

        container.register(MqTopology.QUEUE_REBUILD, (routingKey, body) -> {
        }, 5);

        verify(channel).basicQos(5);
    }

    @Test
    void restartsConsumersOnNewConnectionWithFreshChannel() throws Exception {
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });

        Channel newChannel = mock(Channel.class);
        when(provider.newConsumerChannel()).thenReturn(newChannel);
        capturedListener().onConnected();

        // 新连接上重新拉起一次（旧 channel 已随旧连接失效），而不是在同一 channel 上重复 basicConsume
        verify(channel, times(1)).basicConsume(anyString(), anyBoolean(),
                any(DeliverCallback.class), any(CancelCallback.class));
        verify(newChannel, times(1)).basicConsume(anyString(), anyBoolean(),
                any(DeliverCallback.class), any(CancelCallback.class));
    }

    // ==================== 失败出口（feed3-T32：本地有限重试 + 退避） ====================

    @Test
    void acksWithoutRetryAfterSuccessfulHandling() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> calls.incrementAndGet());
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            assertEquals(1, calls.get(), "成功路径不重试");
            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "成功路径无 WARNING");
            assertTrue(probe.atLevel(Level.SEVERE).isEmpty(), "成功路径无 SEVERE");
        } finally {
            probe.detach();
        }
    }

    @Test
    void retriesThenAcksWhenTransientFailureSelfHeals() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MqConsumerContainer container = connectedContainer(2, FAST_BACKOFF_MILLIS);
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
        });
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            assertEquals(2, calls.get(), "首试失败 → 重试 1 次成功（瞬时失败不再一次即出主流程）");
            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            List<LogRecord> warnings = probe.atLevel(Level.WARNING);
            assertEquals(1, warnings.size(), () -> "自愈应恰一条 WARNING，实际: " + probe.records());
            assertTrue(warnings.getFirst().getMessage().contains("重试成功"));
            assertTrue(warnings.getFirst().getMessage().contains("IllegalStateException"),
                    "自愈结论行应带最近失败类型");
            assertNull(warnings.getFirst().getThrown(), "结论行不带栈");
            assertTrue(probe.atLevel(Level.SEVERE).isEmpty(), "自愈路径不得有 SEVERE");
        } finally {
            probe.detach();
        }
    }

    @Test
    void nacksToDeadLetterAfterRetriesExhaustedWithSingleStackedSevere() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MqConsumerContainer container = connectedContainer(2, FAST_BACKOFF_MILLIS);
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            assertEquals(3, calls.get(), "总尝试 = 首试 + 2 次重试（次数有界，无热循环）");
            // requeue=false：重试耗尽才转死信
            verify(channel).basicNack(DELIVERY_TAG, false, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            List<LogRecord> severe = probe.atLevel(Level.SEVERE);
            assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
            assertTrue(severe.getFirst().getMessage().contains("重试 2 次后转死信"));
            assertEquals(1, probe.stackedRecords().size(), "非 Web 线程根捕获必须持栈");
            assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "耗尽路径无 WARNING（中间失败为 FINE 诊断）");
        } finally {
            probe.detach();
        }
    }

    @Test
    void interruptDuringBackoffAbandonsWithoutAckOrNack() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 大退避 + 预置中断标志：sleep 立即抛出，不会真等待；若误入等待路径本用例会显著变慢
        MqConsumerContainer container = connectedContainer(2, HUGE_BACKOFF_MILLIS);
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            Thread.currentThread().interrupt();
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            assertEquals(1, calls.get(), "中断后不得再重试");
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            List<LogRecord> warnings = probe.atLevel(Level.WARNING);
            assertEquals(1, warnings.size(), () -> "应恰一条中断 WARNING，实际: " + probe.records());
            assertTrue(warnings.getFirst().getMessage().contains("中断"));
            assertTrue(probe.atLevel(Level.SEVERE).isEmpty(), "关停中断不是消费失败（无 SEVERE）");
        } finally {
            Thread.interrupted();   // 清除中断标志，避免污染后续用例
            probe.detach();
        }
    }

    /** maxRetries=0 = 显式关闭重试（一期语义）：一次失败即转死信，且不得进入退避等待。 */
    @Test
    void zeroRetriesConfiguredNacksImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MqConsumerContainer container = connectedContainer(0, HUGE_BACKOFF_MILLIS);
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            assertEquals(1, calls.get(), "0 = 关闭重试：只尝试首试");
            verify(channel).basicNack(DELIVERY_TAG, false, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            List<LogRecord> severe = probe.atLevel(Level.SEVERE);
            assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
            assertTrue(severe.getFirst().getMessage().contains("重试 0 次后转死信"));
        } finally {
            probe.detach();
        }
    }

    @Test
    void nackFailureDoesNotPropagate() throws Exception {
        // nack 本身失败（连接已断）：只记 WARNING 兜底，不得向调用方抛
        MqConsumerContainer container = connectedContainer(0, FAST_BACKOFF_MILLIS);
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            throw new IllegalStateException("boom");
        });
        doThrow(new IOException("channel closed"))
                .when(channel).basicNack(anyLong(), anyBoolean(), anyBoolean());
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            assertDoesNotThrow(() ->
                    capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT)));

            List<String> warnings = probe.messagesAtLevel(Level.WARNING);
            assertTrue(warnings.stream().anyMatch(m -> m.contains("死信投递失败")),
                    () -> "应记死信投递失败告警: " + warnings);
        } finally {
            probe.detach();
        }
    }

    // ==================== 关停与参数校验 ====================

    @Test
    void destroyCancelsAndClosesConsumersSwallowingFailures() throws Exception {
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });
        when(channel.isOpen()).thenReturn(true);
        doThrow(new IOException("close failed")).when(channel).close();

        assertDoesNotThrow(container::destroy);

        verify(channel).basicCancel(CONSUMER_TAG);
    }

    @Test
    void destroySkipsCancelWhenChannelAlreadyClosed() throws Exception {
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });
        when(channel.isOpen()).thenReturn(false);

        container.destroy();

        verify(channel, never()).basicCancel(any());
        verify(channel).close();
    }

    @Test
    void registerRejectsBlankArguments() {
        MqConsumerContainer container = new MqConsumerContainer(provider);

        assertThrows(IllegalArgumentException.class,
                () -> container.register(null, (routingKey, body) -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> container.register(MqTopology.QUEUE_PUSH, null));
    }

    /** 负数重试参数构造即拒（0 合法 = 关闭重试 / 不等待；负数无意义）。 */
    @Test
    void negativeRetryParamsFailFastOnConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new MqConsumerContainer(provider, -1, 1000L));
        assertThrows(IllegalArgumentException.class, () -> new MqConsumerContainer(provider, 1, -1L));
        assertNotNull(new MqConsumerContainer(provider, 0, 0L), "0 值合法（显式语义）");
    }
}