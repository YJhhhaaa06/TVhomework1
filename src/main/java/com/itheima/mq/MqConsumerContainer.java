package com.itheima.mq;

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
 * 处理失败一次性转死信队列。
 *
 * <p><b>失败出口（红线：消费失败不得无限热循环）</b>：处理器抛异常 → 记 SEVERE + 栈
 * （非 Web 线程根捕获，见 {@code LOG_CONVENTION} §3.1）→ {@code basicNack(requeue=false)}，
 * 消息经 DLX 一次性进入死信队列，<b>不重新入队</b>。一期不做有限重试 / 退避（属三期重试治理），
 * 故结构上不存在热循环路径。
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

    /** IoC 注入构造：形参必须为具体类（IoC 按具体类解析依赖）。 */
    @InjectConstructor
    public MqConsumerContainer(MqConnectionManager connectionManager) {
        this.provider = connectionManager;
    }

    /** 包级可见：供单测注入 mock 连接提供者，无需真实 broker。 */
    MqConsumerContainer(MqConnectionProvider provider) {
        this.provider = provider;
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
            try {
                handler.handle(routingKey, delivery.getBody());
            } catch (Exception e) {
                // 非 Web 线程根捕获（LOG_CONVENTION §3.1）：任务体最外层必须有 try/catch 记 SEVERE + 栈
                LOGGER.log(Level.SEVERE, "MQ 消费失败，消息转死信: queue=" + queue
                        + ", routingKey=" + routingKey, e);
                try {
                    // requeue=false → 经 DLX 进死信队列（一次性出队，不重入队）
                    channel.basicNack(deliveryTag, false, false);
                } catch (Exception nackError) {
                    LOGGER.log(Level.WARNING, "MQ 死信投递失败: queue=" + queue
                            + ", " + nackError.getMessage(), nackError);
                }
                return;
            }
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "MQ 消息确认失败: queue=" + queue + ", " + e.getMessage(), e);
            }
        };
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
