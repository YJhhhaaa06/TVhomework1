package com.itheima.mq;

import com.itheima.config.AppConfig;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 消费框架（T17 feed1-17）：注册"队列 → 处理器"，连接可用后启动消费；手动 ack，
 * 处理失败**本地有限重试后退避、耗尽才转死信队列**（feed3-T32）。
 *
 * <p><b>失败出口（收紧口径：有限重试 + 退避，无热循环）</b>：处理器抛异常 → 在**消费线程内**按
 * {@code feed.consume.retry.backoffMillis} 固定退避、重试 {@code feed.consume.retry.maxRetries} 次
 * （T32 口径：次数为个位数，指数曲线收益不显著，固定值使"总等待上界 = 次数 × 本值"简单可证）；
 * 重试成功 ⇒ WARNING 结论行（自愈，无需人介入）后 ack；**仍失败才**记 SEVERE + 栈
 * （非 Web 线程根捕获，见 {@code LOG_CONVENTION} §3.1）→ {@code basicNack(requeue=false)}，
 * 消息经 DLX 进入死信队列（DLQ 自带 TTL，feed3-T32）。fail-fast 口径：重试参数由
 * {@link AppConfig} 校验（负数启动即拒；0 = 显式关闭重试）。
 *
 * <p><b>重试载体与线程口径（为什么同线程 sleep 不违反"不饥饿"红线）</b>：重试载体 = **消费回调
 * 线程内 sleep 退避**（不新增线程 / 不改消费线程模型）。RabbitMQ 客户端对**同一 channel 的回调
 * 串行派发**（见下节），故退避阻塞的只是"本队列自己的下一条"——prefetch=1 下本队列在途消息本就
 * 只有当前这条（broker 不再推送），**退避期间本队列没有可处理的消息被耽搁**；其它队列各有独立
 * channel（独立串行队列），由共享线程池（{@code mq-consumer}，4 线程）独立派发，**不会饥饿**。
 * 当前 2 个队列 < 4 线程的安全边界由本注释登记；队列数若增至 > 线程数需重评（登记于执行回写）。
 *
 * <p><b>重试与幂等</b>：重试会**整体重放**处理器（同 routingKey / body）——处理器必须幂等
 * （当前两个业务处理器均满足：{@code INSERT IGNORE} / 窗口整替 / 消息语义本身幂等）。
 *
 * <p><b>关停期中断</b>：{@code shutdownNow} 中断退避等待 ⇒ 放弃重试、**不 ack 也不 nack**
 * （消息保持未确认，随连接 / channel 关闭由 broker 自动回主队列——关停不是消息的错，
 * 不应进死信），记 WARNING 结论行。
 *
 * <p><b>与连接的生命周期解耦</b>：IoC 容器内 {@code init()} 调用顺序不定，故本类同时覆盖
 * 两种时序——① 连接先建立 → {@code init()} 时的"可用即启动"；② 连接后建立 / 惰性重连成功
 * → {@link MqConnectionListener} 回调触发启动（先清启动标记，避免在新连接上重复消费）。
 * 运行期断线由客户端自动恢复处理，此处不需要介入。
 *
 * <p>每个队列各取一条消费 channel（隔离 head-of-line）；同一 channel 的投递回调由客户端
 * 串行派发，故回调内 ack / nack 无需额外加锁。
 */
@Component
public class MqConsumerContainer implements Initializable, Disposable {

    private static final Logger LOGGER = LogUtil.getLogger(MqConsumerContainer.class);

    /** 预取条数（三期可提配置）。 */
    private static final int DEFAULT_PREFETCH = 1;

    private final MqConnectionProvider provider;

    /** 本地重试次数上限（不含首试；0 = 显式关闭重试）与单次退避等待（毫秒）——口径见 {@link AppConfig}。 */
    private final int maxRetries;
    private final long backoffMillis;

    /** 已注册的队列与处理器（串行启动，启动后只读）。 */
    private final Map<String, Spec> specs = new ConcurrentHashMap<>();

    /** 已启动的消费者（queue → channel + consumerTag）。 */
    private final Map<String, Live> lives = new ConcurrentHashMap<>();

    /** 已启动标记，防止"可用即启动"与"连接回调启动"重复 basicConsume。 */
    private final Set<String> startedQueues = ConcurrentHashMap.newKeySet();

    private final Object lifecycleLock = new Object();

    private record Spec(MqMessageHandler handler, int prefetch) {
    }

    private record Live(Channel channel, String consumerTag) {
    }

    /**
     * IoC 注入构造：形参必须为具体类（IoC 按具体类解析依赖）；重试参数取自
     * {@code feed.consume.retry.*}（语义校验在 AppConfig）。
     */
    @InjectConstructor
    public MqConsumerContainer(MqConnectionManager connectionManager) {
        this(connectionManager, AppConfig.getFeedConsumeRetryMaxRetries(),
                AppConfig.getFeedConsumeRetryBackoffMillis());
    }

    /** 包级可见：供单测注入 mock 连接提供者（重试参数取配置默认），无需真实 broker。 */
    MqConsumerContainer(MqConnectionProvider provider) {
        this(provider, AppConfig.getFeedConsumeRetryMaxRetries(), AppConfig.getFeedConsumeRetryBackoffMillis());
    }

    /**
     * 受控构造（单测）：显式指定重试参数，避免默认退避拖慢失败用例。
     *
     * @throws IllegalArgumentException 任一参数为负——负数无意义（0 有显式语义），宁可构造即拒
     *                                  （对齐 {@code MqDeliveryDispatcher} 的先例）
     */
    MqConsumerContainer(MqConnectionProvider provider, int maxRetries, long backoffMillis) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("feed.consume.retry.maxRetries 不得为负数: " + maxRetries);
        }
        if (backoffMillis < 0) {
            throw new IllegalArgumentException("feed.consume.retry.backoffMillis 不得为负数: " + backoffMillis);
        }
        this.provider = provider;
        this.maxRetries = maxRetries;
        this.backoffMillis = backoffMillis;
    }

    /** 注册消费者（默认 prefetch）。可在 {@code init()} 之前或之后调用。 */
    public void register(String queue, MqMessageHandler handler) {
        register(queue, handler, DEFAULT_PREFETCH);
    }

    /**
     * 注册消费者；若连接当前可用则立即启动。
     *
     * <p>一期口径：同一队列重复注册会<b>替换处理器</b>，但已启动的消费者不会重启（新处理器要等
     * 下一次重连、`onConnected` 重新拉起时生效）；一期每队列只注册一次，无实际影响。
     */
    public void register(String queue, MqMessageHandler handler, int prefetch) {
        if (queue == null || queue.isEmpty() || handler == null) {
            throw new IllegalArgumentException("queue / handler 不能为空");
        }
        specs.put(queue, new Spec(handler, Math.max(1, prefetch)));
        if (provider.isAvailable()) {
            synchronized (lifecycleLock) {
                startPendingLocked();
            }
        }
    }

    @Override
    public void init() {
        provider.addConnectionListener(this::onConnected);
        if (provider.isAvailable()) {
            synchronized (lifecycleLock) {
                startPendingLocked();
            }
        }
    }

    /** 关停：逐条 best-effort 取消消费者并关 channel；每步吞异常，不阻塞其它 Bean 的销毁。 */
    @Override
    public void destroy() {
        synchronized (lifecycleLock) {
            for (Live live : lives.values()) {
                try {
                    if (live.channel().isOpen()) {
                        live.channel().basicCancel(live.consumerTag());
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "MQ 消费者取消失败: " + e.getMessage(), e);
                }
                closeChannelQuietly(live.channel());
            }
            lives.clear();
            startedQueues.clear();
        }
    }

    // ==================== 内部实现 ====================

    /** 新连接已建立：旧 channel 随旧连接失效，重置启动标记后按注册表重新拉起。 */
    private void onConnected() {
        synchronized (lifecycleLock) {
            lives.clear();
            startedQueues.clear();
            startPendingLocked();
        }
    }

    private void startPendingLocked() {
        for (Map.Entry<String, Spec> entry : specs.entrySet()) {
            String queue = entry.getKey();
            if (startedQueues.contains(queue)) {
                continue;
            }
            if (startLocked(queue, entry.getValue())) {
                startedQueues.add(queue);
            }
        }
    }

    private boolean startLocked(String queue, Spec spec) {
        Channel channel = provider.newConsumerChannel();
        if (channel == null) {
            return false;
        }
        try {
            channel.basicQos(spec.prefetch());
            String consumerTag = channel.basicConsume(queue, false,
                    deliver(queue, spec.handler(), channel), (CancelCallback) consumerTagUnused -> {
                    });
            lives.put(queue, new Live(channel, consumerTag));
            LOGGER.log(Level.INFO, "MQ 消费者已启动: queue=" + queue + ", prefetch=" + spec.prefetch());
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MQ 消费者启动失败: queue=" + queue + ", " + e.getMessage(), e);
            closeChannelQuietly(channel);
            return false;
        }
    }

    private DeliverCallback deliver(String queue, MqMessageHandler handler, Channel channel) {
        return (consumerTag, delivery) -> {
            String routingKey = delivery.getEnvelope().getRoutingKey();
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            // 首试（feed3-T32：失败不再直接出主流程，进入下方的本地有限重试）
            Exception lastFailure = tryHandle(handler, routingKey, delivery.getBody());
            if (lastFailure != null) {
                logAttemptFailure(queue, routingKey, 1, lastFailure);
                // 本地有限重试（同线程 sleep 退避；0 = 显式关闭重试，直接耗尽）
                for (int retry = 1; retry <= maxRetries; retry++) {
                    if (!sleepBackoff(queue, routingKey)) {
                        // 关停中断：放弃重试、不 ack 也不 nack——消息保持未确认，
                        // 随连接 / channel 关闭由 broker 自动回主队列（关停不是消息的错）
                        return;
                    }
                    Exception previousFailure = lastFailure;   // 自愈结论行的"最近失败"取重试前那次
                    lastFailure = tryHandle(handler, routingKey, delivery.getBody());
                    if (lastFailure == null) {
                        // 自愈：不需要人介入（§3.1-② 的"重试成功" = WARNING），结论行不带栈
                        LOGGER.log(Level.WARNING, "MQ 消费重试成功（第 " + retry + " 次重试，共尝试 "
                                + (retry + 1) + " 次）: queue=" + queue + ", routingKey=" + routingKey
                                + ", 最近失败=" + lastFailureType(previousFailure));
                        ackQuietly(channel, queue, deliveryTag);
                        return;
                    }
                    logAttemptFailure(queue, routingKey, retry + 1, lastFailure);
                }
                // 重试耗尽：转死信（最终失败的根捕获，SEVERE + 栈为唯一持栈点）
                LOGGER.log(Level.SEVERE, "MQ 消费失败，重试 " + maxRetries + " 次后转死信: queue="
                        + queue + ", routingKey=" + routingKey, lastFailure);
                nackToDeadLetter(channel, queue, deliveryTag);
                return;
            }
            ackQuietly(channel, queue, deliveryTag);
        };
    }

    /**
     * 单次处理尝试：成功返回 null，失败返回异常（不在此记录——由调用方按尝试序号 / 终态分级记录）。
     */
    private static Exception tryHandle(MqMessageHandler handler, String routingKey, byte[] body) {
        try {
            handler.handle(routingKey, body);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * 退避等待（固定退避）。
     *
     * @return true = 等待完成可继续重试；false = 被中断（应用关停中，调用方应放弃重试、不确认消息）
     */
    private boolean sleepBackoff(String queue, String routingKey) {
        try {
            Thread.sleep(backoffMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "MQ 消费重试等待被中断（应用关停中），放弃重试、消息不确认"
                    + "（随连接关闭回主队列）: queue=" + queue + ", routingKey=" + routingKey);
            return false;
        }
    }

    /** 每次失败尝试的诊断行（FINE）：序号 + 异常类型 / 消息，不带栈（最终失败的栈由终态记录持有）。 */
    private static void logAttemptFailure(String queue, String routingKey, int attempt, Exception failure) {
        LOGGER.fine("MQ 消费尝试失败: queue=" + queue + ", routingKey=" + routingKey
                + ", attempt=" + attempt + ", " + lastFailureType(failure));
    }

    /** 异常的一句话形态（类名: 消息；消息为空时只留类名）——诊断行与自愈结论行共用。 */
    private static String lastFailureType(Exception failure) {
        String message = failure.getMessage();
        return (message == null || message.isEmpty())
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }

    private void ackQuietly(Channel channel, String queue, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MQ 消息确认失败: queue=" + queue + ", " + e.getMessage(), e);
        }
    }

    private void nackToDeadLetter(Channel channel, String queue, long deliveryTag) {
        try {
            // requeue=false → 经 DLX 进死信队列（不重入队；有限重试已在上方完成，无热循环）
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception nackError) {
            LOGGER.log(Level.WARNING, "MQ 死信投递失败: queue=" + queue
                    + ", " + nackError.getMessage(), nackError);
        }
    }

    private void closeChannelQuietly(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "关闭 MQ 消费 channel 失败: " + e.getMessage());
        }
    }
}
