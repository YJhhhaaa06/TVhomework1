package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.follow.model.dto.InboxRebuildMessage;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.mq.MqConsumerContainer;
import com.itheima.mq.MqTopology;
import com.itheima.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱重建消费者（feed1-19 T19）：接收 {@code feed.rebuild.queue} 上的重建指令，驱动
 * {@link FeedRebuildService#rebuildInbox(long)} 执行三步重建。
 *
 * <p><b>挂载方式</b>：{@code @Component} + {@link Initializable}（**不动 web.xml / IoC 扫描**，
 * 同 {@code FeedPushConsumer}）。{@code register} 可在 {@link MqConsumerContainer#init()} 前后
 * 任一时点调用——容器同时覆盖"可用即启动"与"连接回调启动"两条时序，故与容器之间
 * **不依赖 IoC 初始化顺序**。
 *
 * <p><b>init() 绝不抛</b>：IoC 会把 {@code init()} 异常包成 RuntimeException 上抛并**阻断 Tomcat 启动**，
 * 故注册动作整体 try/catch（降级 WARNING；MQ 不可用时本就注册不上，关注 / 取关与读路径均不受影响）。
 *
 * <p><b>失败出口</b>：本类 handler **只对"载荷不可用"抛异常**（空载荷 / 非法 JSON）——交由容器根捕获
 * 记 SEVERE + 栈后 {@code basicNack(requeue=false)} 一次性转死信（保留证据、不静默丢弃、不重入队）；
 * 重建过程自身的 Redis / DB 失败**已在 {@link FeedRebuildService} 内降级吞掉**（裁决：ACK），
 * 故不会走到死信出口。本类**不重复记栈**（§3.1 附加纪律 2：一次失败只允许一条带堆栈的记录）。
 *
 * <p><b>通配绑定前向兼容</b>：{@code feed.rebuild.queue} 以 {@code feed.rebuild.#} 通配绑定，
 * 将来新增重建类消息会一并投到本队列 → 非 {@code feed.rebuild.inbox} 的消息只记 FINE 诊断后跳过，
 * 不做误解析（也不投死信，避免未知类型在 DLQ 堆积）。
 */
@Component
public class FeedRebuildConsumer implements Initializable {

    private static final Logger LOGGER = LogUtil.getLogger(FeedRebuildConsumer.class);

    private final MqConsumerContainer container;
    private final JacksonCodec codec;
    private final FeedRebuildService rebuildService;

    @InjectConstructor
    public FeedRebuildConsumer(MqConsumerContainer container, JacksonCodec codec,
                               FeedRebuildService rebuildService) {
        this.container = container;
        this.codec = codec;
        this.rebuildService = rebuildService;
    }

    @Override
    public void init() {
        try {
            container.register(MqTopology.QUEUE_REBUILD, this::handle);
        } catch (Exception e) {
            // IoC 红线：init() 异常会阻断 Tomcat 启动，故注册失败只降级
            LOGGER.log(Level.WARNING, "收件箱重建消费者注册失败（MQ 降级，关注/取关与读路径均不受影响）", e);
        }
    }

    /**
     * 消费回调（MQ 消费线程，非 Web 线程；由容器保证手动 ack / 失败转死信）。
     */
    void handle(String routingKey, byte[] body) {
        if (!MqTopology.RK_REBUILD_INBOX.equals(routingKey)) {
            LOGGER.fine("收件箱重建收到非收件箱重建消息，跳过: routingKey=" + routingKey);
            return;
        }
        String json = (body == null) ? null : new String(body, StandardCharsets.UTF_8);
        InboxRebuildMessage message = codec.fromJson(json, InboxRebuildMessage.class);
        if (message == null) {
            // 空载荷属"消息不可用"，抛出 → 容器转死信（保留证据，不静默丢弃）
            throw new IllegalArgumentException("收件箱重建消息载荷为空");
        }
        rebuildService.rebuildInbox(message.userId());
    }
}
