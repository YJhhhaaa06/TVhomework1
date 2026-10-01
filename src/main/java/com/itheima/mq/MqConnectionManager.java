package com.itheima.mq;

import com.itheima.config.AppConfig;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RabbitMQ 连接管理（T17 feed1-17）：连接建立 / 拓扑声明编排 / 发布 channel /
 * 消费线程池 / 降级与惰性重连 / IoC 生命周期。
 *
 * <p><b>降级（一期红线：MQ 不可用不得阻断启动与业务链路）</b>：{@link #init()} 全程不抛异常
 * ——{@code IocContainer.invokeInitializable} 会把 {@code init()} 抛出的异常包成
 * RuntimeException 上抛并<b>阻断 Tomcat 启动</b>，故此处失败只记 WARNING + 栈后返回。
 * {@code isAvailable()} 不缓存布尔、直接读 {@code Connection.isOpen()}（本地状态、无 I/O），
 * 断线自动变 false、客户端自动恢复后自动变 true。发布侧 {@link MqPublisher} 在任何失败下都只记日志。
 *
 * <p><b>恢复策略（一期拍板，2026-09-26）</b>：
 * <ul>
 *   <li>运行期断线 → 客户端 automatic/topology recovery（自动重连 + 重声明拓扑 + 恢复消费者），
 *       本类不介入；</li>
 *   <li>"首次连不上"或客户端恢复失败 → {@link #ensureConnected()} 惰性重连：
 *       冷却窗口（默认 30s）+ {@code tryLock} 保证并发下最多一个线程真正尝试，
 *       其余线程立即短路返回（不排队、不阻塞请求链路）；</li>
 *   <li>刻意不引入后台常驻重连线程（避免非守护线程影响 Tomcat 关停，且影子期发布量低）。</li>
 * </ul>
 *
 * <p><b>超时必须封顶</b>：{@code ConnectionFactory} 默认连接超时 60s，若不在建厂时设短值，
 * broker 不可用时 Tomcat 启动线程会阻塞 60s——直接违反"不得阻断启动"。故统一取
 * {@code rabbitmq.connection.timeoutMs}（默认 2000ms，同时用于握手）。
 *
 * <p><b>生命周期</b>：{@code @Component} + {@code Initializable}/{@code Disposable}，
 * 经 IoC 自动挂载（不动 web.xml / IoC 扫描）；{@code destroy()} 每步独立吞异常、绝不外抛。
 */
@Component
public class MqConnectionManager implements MqConnectionProvider, Initializable, Disposable {

    private static final Logger LOGGER = LogUtil.getLogger(MqConnectionManager.class);

    /** 惰性重连冷却（三期可提配置）。 */
    private static final long RECONNECT_COOLDOWN_MILLIS = 30_000L;

    /** 连接关闭的有界等待，避免关停时长时间挂住。 */
    private static final int CLOSE_TIMEOUT_MILLIS = 5_000;

    /** 消费线程池大小（三期可提配置）。 */
    private static final int CONSUMER_THREADS = 4;

    private static final long CONSUMER_SHUTDOWN_WAIT_MILLIS = 2_000L;

    private final ConnectionFactory connectionFactory;
    private final MqTopologyDeclarer declarer;
    private final ExecutorService consumerExecutor;
    private final LongSupplier clock;
    private final long reconnectCooldownMillis;

    private final ReentrantLock connectLock = new ReentrantLock();
    private final List<MqConnectionListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Connection connection;
    private volatile Channel publisherChannel;

    /** 上次"真正尝试建连"的时刻（冷却计时起点），由 {@link #clock} 提供以便单测确定性推进。 */
    private volatile long lastAttemptMillis;

    /** 关停标志：令并发 {@link #ensureConnected()} 立即短路，不再发起重连。 */
    private volatile boolean shuttingDown;

    /** IoC 注入构造：连接参数与超时取自 {@code rabbitmq.*}。 */
    @InjectConstructor
    public MqConnectionManager(MqTopologyDeclarer declarer) {
        this(createConnectionFactory(), declarer, newConsumerExecutor(),
                System::currentTimeMillis, RECONNECT_COOLDOWN_MILLIS);
    }

    /** 包级可见：供单测注入 mock 连接工厂 / 受控时钟 / 短冷却，无需真实 broker。 */
    MqConnectionManager(ConnectionFactory connectionFactory, MqTopologyDeclarer declarer,
                        ExecutorService consumerExecutor, LongSupplier clock,
                        long reconnectCooldownMillis) {
        this.connectionFactory = connectionFactory;
        this.declarer = declarer;
        this.consumerExecutor = consumerExecutor;
        this.clock = clock;
        this.reconnectCooldownMillis = reconnectCooldownMillis;
        // 消费回调由客户端派发到本线程池（守护线程，见 newConsumerExecutor）
        connectionFactory.setSharedExecutor(consumerExecutor);
    }

    // ==================== 生命周期 ====================

    /** 启动建连 + 声明拓扑；失败只记 WARNING（带栈）后返回——绝不抛穿阻断应用启动。 */
    @Override
    public void init() {
        try {
            establish();
            LOGGER.log(Level.INFO, "RabbitMQ 已连接，拓扑已声明（push / rebuild / DLQ）");
        } catch (Exception e) {
            lastAttemptMillis = clock.getAsLong();
            LOGGER.log(Level.WARNING, "RabbitMQ 不可用，已降级（应用照常启动，消息投递与消费停用，"
                    + (reconnectCooldownMillis / 1000) + "s 后按需惰性重连）: " + e.getMessage(), e);
        }
    }

    /** 关停：发布 channel → 连接 → 消费线程池；每步独立吞异常，不阻塞其它 Bean 的销毁。 */
    @Override
    public void destroy() {
        shuttingDown = true;

        Channel channel = this.publisherChannel;
        this.publisherChannel = null;
        closeChannelQuietly(channel);

        Connection conn = this.connection;
        this.connection = null;
        closeConnectionQuietly(conn);

        consumerExecutor.shutdownNow();
        try {
            if (!consumerExecutor.awaitTermination(CONSUMER_SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.warning("MQ 消费线程池未在 2s 内终止（线程为守护线程，不影响 JVM 退出）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.log(Level.INFO, "RabbitMQ 连接与消费线程池已关闭");
    }

    // ==================== MqConnectionProvider ====================

    @Override
    public boolean isAvailable() {
        Connection conn = this.connection;
        return conn != null && conn.isOpen();
    }

    @Override
    public boolean ensureConnected() {
        if (isAvailable()) {
            return true;
        }
        if (shuttingDown) {
            return false;
        }
        long now = clock.getAsLong();
        if (now - lastAttemptMillis < reconnectCooldownMillis) {
            return false;
        }
        if (!connectLock.tryLock()) {
            // 已有线程在重连：本线程不排队、立即走降级（保证请求链路不被重连阻塞）
            return false;
        }
        try {
            if (isAvailable()) {
                return true;
            }
            if (clock.getAsLong() - lastAttemptMillis < reconnectCooldownMillis) {
                return false;
            }
            establish();
            LOGGER.log(Level.INFO, "RabbitMQ 重连成功，拓扑与消费已恢复");
            return true;
        } catch (Exception e) {
            lastAttemptMillis = clock.getAsLong();
            LOGGER.log(Level.WARNING, "RabbitMQ 重连失败，" + (reconnectCooldownMillis / 1000)
                    + "s 内不再重试（期间业务走降级）: " + e.getMessage(), e);
            return false;
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public Channel publisherChannel() {
        return publisherChannel;
    }

    @Override
    public Channel newConsumerChannel() {
        Connection conn = this.connection;
        if (conn == null || !conn.isOpen()) {
            return null;
        }
        try {
            return conn.createChannel();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "MQ 消费 channel 创建失败: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void addConnectionListener(MqConnectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 建立连接 + 建发布 channel（confirm 模式）+ 声明拓扑，成功后一次性对外可见并通知监听器。
     *
     * <p>先声明拓扑再赋值字段：保证 {@code isAvailable()} 为 true 时拓扑一定已就绪。
     * 任一步失败则关掉半成品连接（避免连接泄漏）并把异常抛给调用方决定降级口径。
     */
    private void establish() throws Exception {
        // 释放旧连接：显式 close 会一并取消客户端的自动恢复，
        // 避免"客户端自动恢复"与"本类惰性重连"并存产生双连接
        Channel oldChannel = this.publisherChannel;
        Connection oldConnection = this.connection;
        this.publisherChannel = null;
        this.connection = null;
        closeChannelQuietly(oldChannel);
        closeConnectionQuietly(oldConnection);

        lastAttemptMillis = clock.getAsLong();

        Connection conn = connectionFactory.newConnection();
        Channel publish = null;
        try {
            publish = conn.createChannel();
            publish.confirmSelect();
            declarer.declare(publish);
            this.connection = conn;
            this.publisherChannel = publish;
            if (shuttingDown) {
                // 关停竞态（评审建议）：destroy() 若在本次赋值前已跑完，它只关掉了"当时"的引用，
                // 本次新建的连接无人认领 —— 必须自清，避免留下活连接拖住 JVM 退出
                this.connection = null;
                this.publisherChannel = null;
                closeChannelQuietly(publish);
                closeConnectionQuietly(conn);
                throw new IOException("应用关停中，放弃本次 MQ 连接");
            }
        } catch (Exception e) {
            closeChannelQuietly(publish);
            closeConnectionQuietly(conn);
            throw e;
        }
        notifyConnected();
    }

    private void notifyConnected() {
        for (MqConnectionListener listener : listeners) {
            try {
                listener.onConnected();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "MQ 连接回调执行失败: " + e.getMessage(), e);
            }
        }
    }

    private static ConnectionFactory createConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(AppConfig.getRabbitmqHost());
        factory.setPort(AppConfig.getRabbitmqPort());
        factory.setUsername(AppConfig.getRabbitmqUsername());
        factory.setPassword(AppConfig.getRabbitmqPassword());
        factory.setVirtualHost(AppConfig.getRabbitmqVhost());
        // 封顶连接/握手超时（默认 60s 会阻塞 Tomcat 启动线程）
        int timeoutMs = AppConfig.getRabbitmqConnectionTimeoutMs();
        factory.setConnectionTimeout(timeoutMs);
        factory.setHandshakeTimeout(timeoutMs);
        // 运行期断线自动重连 + 自动重声明拓扑与消费者
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        return factory;
    }

    private static ExecutorService newConsumerExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "mq-consumer");
            // 守护线程：即使 shutdownNow 未能终止，也不会阻塞 Tomcat / JVM 退出
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(CONSUMER_THREADS, threadFactory);
    }

    private void closeChannelQuietly(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception e) {
            // 关停/重连路径下 channel 已失效属常态，只留诊断不落盘
            LOGGER.log(Level.FINE, "关闭 MQ channel 失败: " + e.getMessage());
        }
    }

    private void closeConnectionQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close(CLOSE_TIMEOUT_MILLIS);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭 MQ 连接失败: " + e.getMessage(), e);
        }
    }
}
