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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * {@link MqConsumerContainer} 单测（T17 feed1-17）：验收红线"消费失败不得无限热循环"
 * 用 {@code ArgumentCaptor} 锁死 {@code basicNack(tag, false, false)}。
 */
class MqConsumerContainerTest {

    private static final long DELIVERY_TAG = 7L;

    private static final String CONSUMER_TAG = "ctag";

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
        when(provider.isAvailable()).thenReturn(true);
        MqConsumerContainer container = new MqConsumerContainer(provider);
        container.init();
        return container;
    }

    /** 消费者先注册、连接后建立（"连接回调启动"路径）。 */
    private MqConsumerContainer waitingContainer() {
        when(provider.isAvailable()).thenReturn(false);
        MqConsumerContainer container = new MqConsumerContainer(provider);
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

    // ==================== 失败出口 ====================

    @Test
    void acksAfterSuccessfulHandling() throws Exception {
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
        });

        capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void nacksWithoutRequeueAndLogsStackedSevereWhenHandlerFails() throws Exception {
        MqConsumerContainer container = connectedContainer();
        container.register(MqTopology.QUEUE_PUSH, (routingKey, body) -> {
            throw new IllegalStateException("boom");
        });
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConsumerContainer.class));
        try {
            capturedDeliverCallback(channel).handle("tag", delivery(MqTopology.RK_PUSH_CONTENT));

            // requeue=false：一次性转死信，结构上不存在无限热循环
            verify(channel).basicNack(DELIVERY_TAG, false, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            assertEquals(1, probe.atLevel(Level.SEVERE).size());
            assertEquals(1, probe.stackedRecords().size(), "非 Web 线程根捕获必须持栈");
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
}
