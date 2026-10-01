package com.itheima.follow.model.dto;

/**
 * 降级补推指令（feed3-T28-B）：{@code authorId} 由大V降为普通（滞回判定 edge，见
 * {@link com.itheima.user.service.AutoBigVStateService}）⇒ 消费者把该作者**最近 K 条**内容
 * 补写进其**现任粉丝**收件箱。
 *
 * <p><b>投递点</b>：关注 / 取关**事务提交后**（{@link com.itheima.follow.service.FollowService}
 * → {@link com.itheima.follow.service.AuthorBackfillNotifier}），且**只在**状态迁移为
 * {@code DOWNGRADED}（edge，`affected == 1`）时投递——edge 天然唯一（并发"恰一次"由 T28-A 的
 * 行锁 + `affected rows` 保证），故**无需去抖 / 补偿**（缺口补偿统一归 feed3-T33）。
 *
 * <p><b>载荷只放 authorId</b>（不带内容清单、不带粉丝清单）：补推内容在消费时刻按 DB 真相重算
 * （最近 K 条 = {@code feed.inbox.windowPerAuthor}，粉丝 = 消费时刻的现任粉丝，游标迭代），
 * 故载荷不含任何不可重算状态——与 {@link InboxRebuildMessage} 同口径。
 *
 * <p><b>为何落在 follow 域</b>（同 {@link InboxRebuildMessage} 的包环理由）：投递封装是
 * "关注 / 取关的提交后副作用"，放在 {@code com.itheima.follow} 可让 **follow 域不依赖 feed 域**
 * （只依赖 {@code mq} / {@code cache}）；补推的**执行侧**（消费者 + 写扩散）仍在
 * {@code com.itheima.feed}，由 feed 单向依赖本类。
 */
public record AuthorBackfillMessage(long authorId) {
}