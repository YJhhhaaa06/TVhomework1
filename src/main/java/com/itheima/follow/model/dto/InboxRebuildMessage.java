package com.itheima.follow.model.dto;

/**
 * 收件箱重建指令（feed1-19 T19）：{@code userId} 的收件箱需要**全量重建**。
 *
 * <p><b>投递点</b>：关注 / 取关**事务提交后**（{@link com.itheima.follow.service.FollowService}
 * → {@link com.itheima.follow.service.InboxRebuildNotifier}）——关系变化后，该用户收件箱的
 * "关注者内容快照"已失效，须重建。
 *
 * <p><b>载荷只放 userId</b>（不带内容快照、不带关注关系数据）：收件箱是**派生副本**，
 * 内容由接收侧按 DB 真相反查（{@code DEL → DB 重查 → ZADD 合并}），故载荷不含任何
 * 不可重算状态——这是"收件箱可由 DB 重算"这一性质（NEEDS 4.0 收纳边界）在消息契约上的体现。
 *
 * <p><b>为何落在 follow 域</b>：投递封装是"关注/取关的提交后副作用"，放在
 * {@code com.itheima.follow} 可让 **follow 域不依赖 feed 域**（只依赖 {@code mq} / {@code cache}），
 * 避免新增 {@code follow.service ⇄ feed.service} 跨域包环；重建的**执行侧**（消费者 + 三步重建）
 * 仍在 {@code com.itheima.feed}，由 feed 单向依赖本类。
 */
public record InboxRebuildMessage(long userId) {
}
