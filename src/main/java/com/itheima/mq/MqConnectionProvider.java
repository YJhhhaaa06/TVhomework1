package com.itheima.mq;

import com.rabbitmq.client.Channel;

/**
 * 连接与 channel 提供者接缝（T17 feed1-17）。
 *
 * <p>两个用途：① 单测接缝——{@link MqPublisher} / {@link MqConsumerContainer} 依赖本接口，
 * 测试可直接 mock，无需真实 broker；② T18/T19 复用——业务侧只需依赖本接口取发布/消费 channel。
 *
 * <p>注意：IoC 容器按<b>具体类</b>解析构造器依赖，因此 {@code @InjectConstructor} 的形参
 * 必须是 {@link MqConnectionManager} 而非本接口（接口仅用于测试用第二构造器）。
 */
public interface MqConnectionProvider {

    /** 当前是否有可用连接（本地状态读，无 I/O）。 */
    boolean isAvailable();

    /**
     * 确保连接可用（不抛异常）：可用直接返回 true；不可用且不在冷却窗口内时做一次惰性重连。
     *
     * @return true = 可用；false = 当前降级（调用方应直接走降级路径，不访问 broker）
     */
    boolean ensureConnected();

    /** 发布用 channel（已开 publisher confirm）；不可用时返回 null。 */
    Channel publisherChannel();

    /** 新建一条消费用 channel；不可用或创建失败时返回 null。 */
    Channel newConsumerChannel();

    /** 注册"连接建立"监听器。 */
    void addConnectionListener(MqConnectionListener listener);
}
