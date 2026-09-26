package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.feed.model.dto.FeedPushMessage;
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
 * 写扩散消费者（feed1-18 T18；feed2-21 T21 起落库侧改为 {@link FeedInboxWriter}）：接收
 * {@code feed.push.queue} 上的内容发布事件，写各粉丝收件箱（DB 真相 + 缓存失效）。
 *
 * <p><b>挂载方式</b>：{@code @Component} + {@link Initializable}（**不动 web.xml / IoC 扫描**，
 * 先例 = {@code AppShutDownListener} 的 {@code @WebListener}）。{@code register} 可在
 * {@link MqConsumerContainer#init()} 前后任一时点调用——容器同时覆盖"可用即启动"与
 * "连接回调启动"两条时序，故本类与容器之间**不依赖 IoC 初始化顺序**。
 *
 * <p><b>init() 绝不抛</b>：IoC 会把 {@code init()} 异常包成 RuntimeException 上抛并**阻断 Tomcat 启动**，
 * 故注册动作整体 try/catch（降级 WARNING；MQ 不可用时本就注册不上，读路径也不受影响）。
 *
 * <p><b>失败出口</b>：本类 handler **不吞异常**——解析失败（空载荷 / 非法 JSON）直接抛出，
 * 由 {@link MqConsumerContainer} 的任务体最外层根捕获记 SEVERE + 栈后
 * {@code basicNack(requeue=false)} 一次性转死信（不重入队，结构上无热循环）。
 * 本类**不重复记栈**（§3.1 附加纪律 2：一次失败只允许一条带堆栈的记录）。
 *
 * <p><b>通配绑定前向兼容</b>：{@code feed.push.queue} 以 {@code feed.push.#} 通配绑定，
 * 将来新增消息子类型会一并投递到本队列 → 非 {@code feed.push.content} 的消息只记 FINE 诊断后跳过，
 * 不做误解析（也不投死信，避免未知类型在 DLQ 堆积）。
 */
@Component
public class FeedPushConsumer implements Initializable {

    private static final Logger LOGGER = LogUtil.getLogger(FeedPushConsumer.class);

    private final MqConsumerContainer container;
    private final JacksonCodec codec;
    private final FeedInboxWriter inboxWriter;

    @InjectConstructor
    public FeedPushConsumer(MqConsumerContainer container, JacksonCodec codec,
                            FeedInboxWriter inboxWriter) {
        this.container = container;
        this.codec = codec;
        this.inboxWriter = inboxWriter;
    }

    @Override
    public void init() {
        try {
            container.register(MqTopology.QUEUE_PUSH, this::handle);
        } catch (Exception e) {
            // IoC 红线：init() 异常会阻断 Tomcat 启动，故注册失败只降级
            LOGGER.log(Level.WARNING, "写扩散消费者注册失败（MQ 降级，发布与读路径均不受影响）", e);
        }
    }

    /**
     * 消费回调（MQ 消费线程，非 Web 线程；由容器保证手动 ack / 失败转死信）。
     */
    void handle(String routingKey, byte[] body) {
        if (!MqTopology.RK_PUSH_CONTENT.equals(routingKey)) {
            LOGGER.fine("写扩散收到非内容发布消息，跳过: routingKey=" + routingKey);
            return;
        }
        String json = (body == null) ? null : new String(body, StandardCharsets.UTF_8);
        FeedPushMessage message = codec.fromJson(json, FeedPushMessage.class);
        if (message == null) {
            // 空载荷属"消息不可用"，抛出 → 容器转死信（保留证据，不静默丢弃）
            throw new IllegalArgumentException("写扩散消息载荷为空");
        }
        inboxWriter.fanout(message.contentId(), message.authorId());
    }
}
