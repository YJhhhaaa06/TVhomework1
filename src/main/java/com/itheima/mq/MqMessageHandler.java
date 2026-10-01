package com.itheima.mq;

/**
 * 消费回调接缝（T17 feed1-17）：业务侧（T18 写扩散 / T19 重建）实现本接口并注册到
 * {@link MqConsumerContainer}。
 *
 * <p>抛出的异常由消费框架统一治理（feed3-T32 起）：先在**消费线程内退避重试**
 * （{@code feed.consume.retry.*}，次数有界、总等待有界），仍失败才记 SEVERE + 栈后
 * {@code basicNack(requeue=false)} 转死信队列；重试成功记 WARNING 结论行后 ack。
 *
 * <p><b>幂等要求（T32）</b>：重试会以同一 {@code routingKey / body} **整体重放**本处理器——
 * 实现必须幂等（重复执行无副作用），否则重试会放大不一致。
 */
@FunctionalInterface
public interface MqMessageHandler {

    /**
     * 处理一条消息。
     *
     * @param routingKey 消息路由键（死信路由键恒为 {@link MqTopology#RK_DLQ}）
     * @param body       消息体（业务侧自行反序列化）
     * @throws Exception 处理失败——由框架有限重试，耗尽后转死信（不重入队）
     */
    void handle(String routingKey, byte[] body) throws Exception;
}
