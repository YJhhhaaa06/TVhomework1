package com.itheima.mq;

/**
 * 消费回调接缝（T17 feed1-17）：业务侧（T18 写扩散 / T19 重建）实现本接口并注册到
 * {@link MqConsumerContainer}。
 *
 * <p>抛出的异常由消费框架统一处理：记 SEVERE + 栈（非 Web 线程根捕获），
 * 随后 {@code basicNack(requeue=false)} 转死信队列。
 */
@FunctionalInterface
public interface MqMessageHandler {

    /**
     * 处理一条消息。
     *
     * @param routingKey 消息路由键（死信路由键恒为 {@link MqTopology#RK_DLQ}）
     * @param body       消息体（业务侧自行反序列化）
     * @throws Exception 处理失败——由框架转死信，不会重入队
     */
    void handle(String routingKey, byte[] body) throws Exception;
}
