package com.itheima.mq;

/**
 * 连接建立回调接缝（T17 feed1-17）。
 *
 * <p>用途：消除"IoC 容器内 {@code init()} 调用顺序不定"带来的顺序依赖——消费者
 * （{@link MqConsumerContainer}）无论先于还是后于连接管理（{@link MqConnectionManager}）
 * 初始化，都能在"连接可用"这个时刻被通知到并启动消费。
 *
 * <p>只在"建立新连接"后触发（首次连接成功、或惰性重连成功）；运行期断线由 amqp-client
 * 的自动恢复处理，不再触发本回调（客户端会自动恢复已注册的消费者）。
 */
@FunctionalInterface
public interface MqConnectionListener {

    /** 新连接已建立且拓扑已声明完成。 */
    void onConnected();
}
