package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.follow.service.FollowCache;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 读侧两路读编排（feed2-23 T23）：**读态闸门 → 收件箱腿 ∪ 大V发件箱腿 → 归并去重降序截断 M**。
 *
 * <p><b>形态（NEEDS 4.0 拍板）</b>：有界窗口 + 两路读——
 * ① 收件箱窗口（推的产物，见 {@link FeedInboxReader}）；
 * ② 大V发件箱窗口（读时拉，见 {@link FeedOutboxReader}）；
 * 两路归并去重后按 contentId 降序、**截断到读侧总窗口 M**（{@code feed.readWindowMax}，默认 300）。
 *
 * <p><b>每次请求都重算整个窗口</b>（不引入"整窗缓存"新层——两腿各自有缓存，窗口本身不缓存），
 * 页级切片由调用方（{@code FeedService}）在结果上做，故**深翻天然只读窗口内**。
 *
 * <p><b>未同步 ⇒ 回退信号</b>：{@code feed_inbox_sync} 无该用户行时返回 {@code synced=false}，
 * 由调用方走既有纯拉（T23 内已接线；"MQ 不可用"这条触发属 T24）。
 *
 * <p><b>归并口径单一来源</b>：直接复用重建侧的
 * {@link FeedRebuildService#mergeDedupSortTrim(List, int)}（去重 + contentId 降序 + 截断），
 * 上限改传 M ⇒ "归并"这一口径全仓只有一份实现（只有**上限参数**按层不同）。
 *
 * <p><b>失败面</b>：闸门 / 收件箱腿的 DB 失败**上抛**（{@code ServerException} ⇒ 500，与切换前的
 * 纯拉一致——读路径 DB 失败不留半条时间线）；大V腿自带降级（fail-open，见 {@link FeedOutboxReader}
 * 与 {@link FeedBigVRouter#isBigVBatch(List)}），**不 500**。本类不重复记栈（一次失败只允许一条
 * 带堆栈记录，§3.1 附加纪律 2）。
 *
 * <p><b>依赖方向</b>：本类在 feed 域、被 {@code content.service.FeedService} 委托调用
 * （content → feed，与既有 {@code ContentService → FeedPushNotifier} 同向，**不新增包环**）。
 */
@Component
public class FeedReadService {

    private final FollowCache followCache;
    private final FeedBigVRouter bigVRouter;
    private final FeedInboxReader inboxReader;
    private final FeedOutboxReader outboxReader;

    @InjectConstructor
    public FeedReadService(FollowCache followCache, FeedBigVRouter bigVRouter,
                           FeedInboxReader inboxReader, FeedOutboxReader outboxReader) {
        this.followCache = followCache;
        this.bigVRouter = bigVRouter;
        this.inboxReader = inboxReader;
        this.outboxReader = outboxReader;
    }

    /**
     * 读侧窗口结果。
     *
     * @param synced    false ⇒ 调用方**回退既有纯拉**（读态闸门未过）
     * @param windowIds 已归并 / 去重 / contentId 降序 / 截断到 M 的可见窗口（{@code synced=false} 时为空）
     */
    public record FeedReadResult(boolean synced, List<Long> windowIds) {
    }

    /**
     * 重算 {@code userId} 的可见窗口（每次调用都重算；见类注释）。
     *
     * @return {@code synced=false}（未同步 → 调用方回退纯拉）或 {@code synced=true} + 窗口（可能为空：
     *         无关注 / 两腿都没内容）
     */
    public FeedReadResult readWindow(long userId) {
        // ① 读态闸门：未同步连关注集都不必读（窗口不可信 ⇒ 交回纯拉）
        if (!inboxReader.isSynced(userId)) {
            return new FeedReadResult(false, Collections.emptyList());
        }

        // ② 关注集（FollowCache 三态读 + miss 回填 + Redis 挂则降级 DB）；空关注 = 空窗
        List<Long> following = followCache.getFollowingIds(userId);
        if (following.isEmpty()) {
            return new FeedReadResult(true, Collections.emptyList());
        }

        // ③ 收件箱腿（已按 contentId 降序）
        List<Long> inboxIds = inboxReader.readInbox(userId);

        // ④ 大V发件箱腿（单点批量判定 → 一次 pipeline 批读；无大V则整腿跳过）
        Set<Long> bigVAuthors = bigVRouter.isBigVBatch(following);

        // ⑤ 归并去重降序截断 M（复用重建侧实现，口径单一来源）
        List<Long> raw = new ArrayList<>(inboxIds);
        if (!bigVAuthors.isEmpty()) {
            raw.addAll(outboxReader.readOutbox(bigVAuthors));
        }
        return new FeedReadResult(true,
                FeedRebuildService.mergeDedupSortTrim(raw, AppConfig.getFeedReadWindowMax()));
    }
}
