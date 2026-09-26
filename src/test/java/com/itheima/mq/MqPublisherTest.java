package com.itheima.mq;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqPublisher} 单测（T17 feed1-17）：验收红线"发布封装确认失败只记日志不抛"
 * 与"不可用时不动 broker"。
 */
class MqPublisherTest {

    private static final byte[] BODY = {1, 2, 3};

    private static MqConnectionProvider availableProvider(Channel channel) {
        MqConnectionProvider provider = mock(MqConnectionProvider.class);
        when(provider.ensureConnected()).thenReturn(true);
        when(provider.publisherChannel()).thenReturn(channel);
        return provider;
    }

    private static MqMessage message() {
        return MqMessage.push(MqTopology.RK_PUSH_CONTENT, BODY);
    }

    @Test
    void fallsBackWithoutTouchingBrokerWhenUnavailable() {
        MqConnectionProvider provider = mock(MqConnectionProvider.class);
        when(provider.ensureConnected()).thenReturn(false);

        assertFalse(new MqPublisher(provider).publish(message()));

        verify(provider, never()).publisherChannel();
    }

    @Test
    void returnsFalseWithoutConnectingForNullOrEmptyBody() {
        MqConnectionProvider provider = mock(MqConnectionProvider.class);
        MqPublisher publisher = new MqPublisher(provider);

        assertFalse(publisher.publish(null));
        assertFalse(publisher.publish(new MqMessage(
                MqTopology.EXCHANGE_PUSH, MqTopology.RK_PUSH_CONTENT, null)));

        verify(provider, never()).ensureConnected();
    }

    @Test
    void returnsFalseWhenPublisherChannelMissing() {
        MqConnectionProvider provider = mock(MqConnectionProvider.class);
        when(provider.ensureConnected()).thenReturn(true);
        when(provider.publisherChannel()).thenReturn(null);

        assertFalse(new MqPublisher(provider).publish(message()));
    }

    @Test
    void returnsTrueWhenBrokerConfirms() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);

        assertTrue(new MqPublisher(availableProvider(channel)).publish(message()));

        verify(channel).basicPublish(eq(MqTopology.EXCHANGE_PUSH), eq(MqTopology.RK_PUSH_CONTENT),
                any(), eq(BODY));
    }

    @Test
    void returnsFalseAndWarnsWhenBrokerDoesNotConfirm() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqPublisher.class));
        try {
            assertFalse(new MqPublisher(availableProvider(channel)).publish(message()));

            assertEquals(1, probe.atLevel(Level.WARNING).size(), "未确认应恰记一条 WARNING");
        } finally {
            probe.detach();
        }
    }

    @Test
    void returnsFalseAndWarnsWhenPublishThrows() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("channel closed")).when(channel)
                .basicPublish(anyString(), anyString(), any(), any());
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqPublisher.class));
        try {
            assertFalse(new MqPublisher(availableProvider(channel)).publish(message()));

            assertEquals(1, probe.atLevel(Level.WARNING).size(), "发布失败应恰记一条 WARNING");
        } finally {
            probe.detach();
        }
    }

    @Test
    void returnsFalseWhenConfirmationTimesOut() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException("timeout"));

        assertFalse(new MqPublisher(availableProvider(channel)).publish(message()));
    }

    @Test
    void returnsFalseAndRestoresInterruptFlagWhenInterrupted() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.waitForConfirms(anyLong())).thenThrow(new InterruptedException("interrupted"));

        assertFalse(new MqPublisher(availableProvider(channel)).publish(message()));

        assertTrue(Thread.interrupted(), "中断位应被恢复（本断言同时清理标志，避免污染其它用例）");
    }
}
