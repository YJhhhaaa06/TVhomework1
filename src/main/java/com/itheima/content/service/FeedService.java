package com.itheima.content.service;

import com.itheima.common.model.dto.PageResult;
import com.itheima.content.dao.ContentDao;
import com.itheima.feed.service.FeedReadService;
import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 关注动态流读服务：**两路读（有界窗口）为主，纯拉为降级**（feed2-23 T23 切读）。
 *
 * <p><b>入口唯一</b>：{@code FeedController} 注入本类（{@code /feed} URL 与信封语义不变）。
 * 每次请求先问 {@link FeedReadService#readWindow(long)}：
 * <ul>
 *   <li>{@code synced=false}（未同步）→ 走 {@link #purePull}（**切换前的实现整体保留**，
 *       分页 / 返回集 / 跳过 null / 异常语义逐字不变）；</li>
 *   <li>{@code synced=true} → 在**归并窗口**（≤ 读侧总窗口 M = {@code feed.readWindowMax}）上切片，
 *       越过窗口即空页（**深翻禁止**），{@code total} = 窗口实际条数。</li>
 * </ul>
 * 两条路径的**内容装载完全共用**（{@link #renderPage}）：事务外批量读缓存 / 点赞，按原序跳过 null。
 *
 * <p><b>降级（纯拉）保留</b>：窗口不可信（未同步）时以纯拉作答——正确性优先、代价是慢；
 * "MQ 不可用"这条触发与对外语义登记属 T24。
 */
@Component
public class FeedService {

    private final FollowCache followCache;
    private final ContentDao contentDao;
    private final ContentCache contentCache;
    private final LikeService likeService;
    private final TransactionTemplate transactionTemplate;
    private final FeedReadService feedReadService;
    private static final Logger LOGGER = LogUtil.getLogger(FeedService.class);

    @InjectConstructor
    public FeedService(FollowCache followCache, ContentDao contentDao,
                       ContentCache contentCache, LikeService likeService,
                       TransactionTemplate transactionTemplate, FeedReadService feedReadService) {
        this.followCache = followCache;
        this.contentDao = contentDao;
        this.contentCache = contentCache;
        this.likeService = likeService;
        this.transactionTemplate = transactionTemplate;
        this.feedReadService = feedReadService;
    }

    /**
     * 关注动态流（feed2-23 T23：**切读**——两路读窗口为主、纯拉为降级）。
     *
     * <p>分页契约：每次请求**重算整个窗口**再切第 {@code page} 页 ⇒ 无需 offset 深翻；
     * {@code total} = 归并窗口实际条数（≤ M）；越过窗口返回空页且 {@code total} 不变。
     *
     * <p>缓存批量读仍**移出 DB 事务**（第五期 T3 口径不变）：事务回调只承载 DB 查询，提交归还连接后，
     * 再在事务外读 {@code contentCache.getContentsBatch}（含 miss 装载）与
     * {@code likeService.batchIsContentLiked}——消除"外层事务持连接 + miss 装载再取新连接"的叠加
     * （自研 {@link TransactionTemplate} 无传播语义，嵌套读各自取新连接）。对外行为零变化
     * （分页/返回集/跳过 null/异常语义一概不变，total==0 与无关注两个早退分支同样不触碰缓存）。
     */
    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
        FeedReadService.FeedReadResult window = feedReadService.readWindow(currentUserId);
        if (!window.synced()) {
            // 读态闸门未过（窗口不可信）→ 既有纯拉（本任务内完成判定 + 回退）
            return purePull(currentUserId, page, pageSize);
        }
        return loadWindowPage(currentUserId, window.windowIds(), page, pageSize);
    }

    /**
     * 窗口路径：在归并窗口上切页（**深翻禁止**）。
     *
     * <p>窗口已按 contentId 降序、去重、截断到 M；越界（{@code offset >= total}）返回**空页**而非报错，
     * 且 {@code total} 保持窗口实际条数（前端据此自然停止翻页）。
     */
    private PageResult<ContentVO> loadWindowPage(long currentUserId, List<Long> windowIds,
                                                 int page, int pageSize) {
        int total = windowIds.size();
        long offset = (long) (page - 1) * pageSize;   // 用 long 防 int 溢出（page 极大时）
        List<Long> pageIds = (pageSize <= 0 || offset >= total)
                ? Collections.emptyList()
                : windowIds.subList((int) offset, Math.min((int) offset + pageSize, total));
        return renderPage(currentUserId, pageIds, total, page, pageSize);
    }

    /**
     * 纯拉降级路径（**切换前的实现整体保留**，逐字不动）：关注 ids → 事务内计数 + 当页 id 查询。
     *
     * <p>对外正确性 = 全量可见（不受有界窗口约束）⇒ 未同步用户不会因切读而"看不到旧内容"。
     */
    private PageResult<ContentVO> purePull(long currentUserId, int page, int pageSize) {
        // 关注列表走关注缓存（FollowCache 内部三态读 + miss 回填 + Redis 挂降级 DB）
        List<Long> followedIds = followCache.getFollowingIds(currentUserId);

        if (followedIds.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
        }

        // T3：事务回调只做 DB 查询，不触碰任何缓存（缓存读见下方事务外段）
        FeedDbData db = transactionTemplate.execute(conn -> {
            try {
                int total = contentDao.countContentByUsers(conn, followedIds);
                if (total == 0) {
                    return new FeedDbData(Collections.emptyList(), 0);
                }

                int offset = (page - 1) * pageSize;
                List<Long> pageIds = contentDao.findContentIdsByUsers(conn, followedIds, offset, pageSize);
                return new FeedDbData(pageIds, total);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "获取关注动态失败, userId=" + currentUserId, e);
                throw new ServerException("获取关注动态失败");
            }
        });

        if (db.total() == 0) {
            return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
        }

        return renderPage(currentUserId, db.pageIds(), db.total(), page, pageSize);
    }

    /**
     * 页内容装载（两路读共用）：**事务外**批量读内容缓存 + 点赞，按原序跳过 null。
     *
     * <p>T8 一趟 pipeline，语义与逐条 getContent 一致；T2 起 miss 装载走批量 loader。
     * ⚠️ 跳过 null 会让返回页**短于 pageSize**（内容已软删 / 缓存空标记）——这不是"到底"，
     * 前端据此判耗尽会提前停住（前端已由 {@code shortPageMeansEnd:false} 处置，见 T23 计划）。
     */
    private PageResult<ContentVO> renderPage(long currentUserId, List<Long> pageIds, int total,
                                             int page, int pageSize) {
        List<ContentVO> contentVOList = new ArrayList<>();
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(pageIds);
        for (Long contentId : pageIds) {
            ContentCacheDTO cached = byId.get(contentId);
            if (cached == null) continue;
            contentVOList.add(contentCache.toContentVO(cached));
        }

        if (!contentVOList.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (ContentVO vo : contentVOList) {
                ids.add(vo.getId());
            }
            Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(currentUserId, ids);
            if (likedMap != null) {
                for (ContentVO vo : contentVOList) {
                    Boolean liked = likedMap.get(vo.getId());
                    vo.setIsLiked(liked != null && liked);
                }
            }
        }

        return new PageResult<>(contentVOList, total, page, pageSize);
    }

    /** 事务内 DB 查询结果（T3：事务回调的返回值载体，缓存读在事务外进行）。 */
    private record FeedDbData(List<Long> pageIds, int total) {
    }

}
