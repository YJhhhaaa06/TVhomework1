package com.itheima.mq;

/**
 * 发布载体（T17 feed1-17）：交换机和路由键由调用方显式给出，消息体为已完成序列化的字节。
 *
 * <p>刻意带 {@code exchange} 字段（而不只给路由键）：四期"通知等场景接入同一 MQ 基建"时
 * 可直接复用 {@link MqPublisher} 的签名，无需再改方法形态；一期的便利构造见
 * {@link #push} / {@link #rebuild}。
 *
 * <p>序列化（JSON、UTF-8）由业务侧在构造前完成，MQ 层不感知业务对象类型。
 */
public record MqMessage(String exchange, String routingKey, byte[] body) {

    /** 写扩散消息（走 {@link MqTopology#EXCHANGE_PUSH}）。 */
    public static MqMessage push(String routingKey, byte[] body) {
        return new MqMessage(MqTopology.EXCHANGE_PUSH, routingKey, body);
    }

    /** 重建消息（走 {@link MqTopology#EXCHANGE_REBUILD}）。 */
    public static MqMessage rebuild(String routingKey, byte[] body) {
        return new MqMessage(MqTopology.EXCHANGE_REBUILD, routingKey, body);
    }
}
