package com.itheima.mq;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqConnectionManager} 单测（T17 feed1-17）：不依赖真实 broker，
 * 用 mock 连接工厂 + 受控时钟覆盖验收的降级 / 拓扑声明 / 惰性重连 / 并发去重 / 关停五条。
 */
class MqConnectionManagerTest {

    private static final long COOLDOWN_MILLIS = 30_000L;

    private ConnectionFactory connectionFactory;
    private MqTopologyDeclarer declarer;
    private ExecutorService consumerExecutor;

    /** 受控时钟：单测里直接推进，避免 sleep。 */
    private long now;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(ConnectionFactory.class);
        declarer = mock(MqTopologyDeclarer.class);
        consumerExecutor = Executors.newSingleThreadExecutor();
        now = 1_000_000L;
    }

    @AfterEach
    void tearDown() {
        consumerExecutor.shutdownNow();
    }

    private MqConnectionManager manager() {
        return new MqConnectionManager(connectionFactory, declarer, consumerExecutor,
                () -> now, COOLDOWN_MILLIS);
    }

    private static Connection openConnection(Channel channel) throws IOException {
        Connection connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.createChannel()).thenReturn(channel);
        return connection;
    }

    // ==================== 降级 ====================

    @Test
    void initDegradesWithStackedWarningWhenBrokerIsDown() throws Exception {
        when(connectionFactory.newConnection()).thenThrow(new IOException("Connection refused"));
        MqConnectionManager manager = manager();
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(MqConnectionManager.class));
        try {
            assertDoesNotThrow(manager::init, "broker 不可用绝不能让 init 抛穿（会阻断应用启动）");

            assertFalse(manager.isAvailable());
            assertNull(manager.publisherChannel());
            assertNull(manager.newConsumerChannel());
            verify(declarer, never()).declare(any());

            // init 是本链的唯一捕获点 → 结论行必须持栈（LOG_CONVENTION §3.1）
            assertEquals(1, probe.atLevel(Level.WARNING).size());
            assertEquals(1, probe.stackedRecords().size());
        } finally {
            probe.detach();
        }
    }

    @Test
    void topologyDeclareFailureDegradesAndClosesHalfOpenConnection() throws Exception {
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection()).thenReturn(connection);
        doThrow(new IOException("access refused")).when(declarer).declare(channel);

        MqConnectionManager manager = manager();
        assertDoesNotThrow(manager::init);

        assertFalse(manager.isAvailable(), "拓扑声明失败不应暴露为可用");
        assertNull(manager.publisherChannel());
        verify(connection).close(anyInt());
    }

    // ==================== 可用路径 ====================

    @Test
    void initEstablishesConnectionDeclaresTopologyAndNotifiesListeners() throws Exception {
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection()).thenReturn(connection);

        MqConnectionManager manager = manager();
        AtomicInteger notified = new AtomicInteger();
        manager.addConnectionListener(notified::incrementAndGet);

        manager.init();

        assertTrue(manager.isAvailable());
        assertSame(channel, manager.publisherChannel());
        verify(channel).confirmSelect();
        verify(declarer).declare(channel);
        assertEquals(1, notified.get());
    }

    // ==================== 惰性重连 ====================

    @Test
    void ensureConnectedDoesNotRetryWithinCooldown() throws Exception {
        when(connectionFactory.newConnection()).thenThrow(new IOException("Connection refused"));
        MqConnectionManager manager = manager();
        manager.init();

        now += COOLDOWN_MILLIS - 1;

        assertFalse(manager.ensureConnected());
        verify(connectionFactory, times(1)).newConnection();
    }

    @Test
    void ensureConnectedRetriesAfterCooldownAndRestoresAvailability() throws Exception {
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection())
                .thenThrow(new IOException("Connection refused"))
                .thenReturn(connection);

        MqConnectionManager manager = manager();
        manager.init();
        assertFalse(manager.isAvailable());

        now += COOLDOWN_MILLIS;

        assertTrue(manager.ensureConnected());
        assertTrue(manager.isAvailable());
        verify(declarer).declare(channel);
        verify(connectionFactory, times(2)).newConnection();
    }

    @Test
    void ensureConnectedFailsFastWhileAnotherThreadIsReconnecting() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection())
                .thenThrow(new IOException("Connection refused"))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return connection;
                });

        MqConnectionManager manager = manager();
        manager.init();
        now += COOLDOWN_MILLIS;

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = threads.submit(manager::ensureConnected);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个线程应进入重连");

            assertFalse(manager.ensureConnected(), "重连进行中时其它线程不应排队等待");

            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            verify(connectionFactory, times(2)).newConnection();
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void ensureConnectedFailsFastWhileAnotherThreadHoldsConnectLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection())
                .thenThrow(new IOException("Connection refused"))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return connection;
                });

        // 冷却 0：让并发的第二个线程能穿过冷却检查，真正命中 tryLock 去重分支
        MqConnectionManager manager = new MqConnectionManager(connectionFactory, declarer,
                consumerExecutor, () -> now, 0L);
        manager.init();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = threads.submit(manager::ensureConnected);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个线程应进入重连");

            assertFalse(manager.ensureConnected(), "重连进行中时其它线程不应排队等待（tryLock 快速失败）");

            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            verify(connectionFactory, times(2)).newConnection();
        } finally {
            threads.shutdownNow();
        }
    }

    // ==================== 关停 ====================

    @Test
    void destroyClosesChannelConnectionAndConsumerExecutor() throws Exception {
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection()).thenReturn(connection);

        MqConnectionManager manager = manager();
        manager.init();

        manager.destroy();

        verify(channel).close();
        verify(connection).close(anyInt());
        assertTrue(consumerExecutor.isShutdown());
        assertFalse(manager.isAvailable());
    }

    @Test
    void destroySwallowsCloseFailures() throws Exception {
        Channel channel = mock(Channel.class);
        Connection connection = openConnection(channel);
        when(connectionFactory.newConnection()).thenReturn(connection);
        doThrow(new IOException("channel already closed")).when(channel).close();
        doThrow(new IOException("connection already closed")).when(connection).close(anyInt());

        MqConnectionManager manager = manager();
        manager.init();

        assertDoesNotThrow(manager::destroy);
        assertTrue(consumerExecutor.isShutdown());
    }

    @Test
    void ensureConnectedDoesNotReconnectAfterDestroy() throws Exception {
        when(connectionFactory.newConnection()).thenThrow(new IOException("Connection refused"));
        MqConnectionManager manager = manager();
        manager.init();
        manager.destroy();

        now += COOLDOWN_MILLIS * 10;

        assertFalse(manager.ensureConnected(), "关停后不得再发起重连");
        verify(connectionFactory, times(1)).newConnection();
    }
}
