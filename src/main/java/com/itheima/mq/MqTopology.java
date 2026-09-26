package com.itheima.mq;

import java.util.Map;

/**
 * RabbitMQ 拓扑与路由命名的唯一源（T17 feed1-17）。
 *
 * <p>口径对齐 {@code cache.CacheKeys}（"命名唯一源"）：交换机 / 队列 / 路由键 / 绑定模式
 * 一律从这里取，业务侧（T18/T19）不得另造字符串字面量。
 *
 * <p><b>拓扑（一期拍板，2026-09-26）</b>：push / rebuild 各用一条 topic 交换机 + 一条队列
 * （队列以 {@code feed.push.#} / {@code feed.rebuild.#} 通配绑定，将来加消息子类型免重声明绑定）；
 * 死信走独立 direct 交换机 {@link #EXCHANGE_DLX} + 独立队列 {@link #QUEUE_DLQ}，
 * 源队列以 {@code x-dead-letter-routing-key} 固定死信路由键（不沿用原 key，避免 DLX 路由不确定）。
 *
 * <p>命名前缀 {@code feed.} 为四期"通知等接入同一 MQ 基建"预留同构扩展位。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 交换机 ====================

    /** 写扩散（fanout）交换机：博主发布内容 → 投递到各粉丝收件箱。 */
    public static final String EXCHANGE_PUSH = "feed.push.exchange";

    /** 重建交换机：收件箱全量重建（关注 / 取关联动、兜底修复）。 */
    public static final String EXCHANGE_REBUILD = "feed.rebuild.exchange";

    /** 死信交换机（DLX）：消费失败的消息一次性转出，不做重入队（一期不做重试治理）。 */
    public static final String EXCHANGE_DLX = "feed.dlx";

    // ==================== 队列 ====================

    public static final String QUEUE_PUSH = "feed.push.queue";

    public static final String QUEUE_REBUILD = "feed.rebuild.queue";

    /** 死信队列：自身不设死信参数（防环）；一期不设 TTL（保留证据，容量议题留三期）。 */
    public static final String QUEUE_DLQ = "feed.dlq";

    // ==================== 路由键 ====================

    /** 内容发布事件路由键。 */
    public static final String RK_PUSH_CONTENT = "feed.push.content";

    /** 收件箱重建任务路由键。 */
    public static final String RK_REBUILD_INBOX = "feed.rebuild.inbox";

    /** 死信路由键（由源队列的 x-dead-letter-routing-key 固定）。 */
    public static final String RK_DLQ = "feed.dlq";

    // ==================== 绑定模式 ====================

    public static final String BIND_PUSH_ALL = "feed.push.#";

    public static final String BIND_REBUILD_ALL = "feed.rebuild.#";

    /**
     * 源队列（push / rebuild）的死信参数：失败消息经 {@link #EXCHANGE_DLX} → {@link #QUEUE_DLQ}。
     */
    public static Map<String, Object> deadLetterArgs() {
        return Map.of(
                "x-dead-letter-exchange", EXCHANGE_DLX,
                "x-dead-letter-routing-key", RK_DLQ);
    }
}
