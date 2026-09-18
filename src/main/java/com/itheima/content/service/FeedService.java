package com.itheima.content.service;

import com.itheima.content.model.dto.PageResult;
import com.itheima.content.dao.ContentDao;
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

@Component
public class FeedService {

    private final FollowCache followCache;
    private final ContentDao contentDao;
    private final ContentCache contentCache;
    private final LikeService likeService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER = LogUtil.getLogger(FeedService.class);

    @InjectConstructor
    public FeedService(FollowCache followCache, ContentDao contentDao,
                       ContentCache contentCache, LikeService likeService,
                       TransactionTemplate transactionTemplate) {
        this.followCache = followCache;
        this.contentDao = contentDao;
        this.contentCache = contentCache;
        this.likeService = likeService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 关注动态流。第五期 T3（治 N2）：**缓存批量读移出 DB 事务**——事务回调只承载 DB 查询
     * （关注者内容总数 + 当前页内容 id），提交归还连接后，再在**事务外**读
     * {@code contentCache.getContentsBatch}（含 miss 装载）与 {@code likeService.batchIsContentLiked}
     * ——消除"外层事务持连接 + miss 装载再取新连接"的叠加（自研 {@link TransactionTemplate}
     * 无传播语义，嵌套读各自取新连接）。对外行为零变化（分页/返回集/跳过 null/异常语义一概不变，
     * total==0 与无关注两个早退分支同样不触碰缓存）。
     */
    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
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

        // 事务外：页内批量读（T8 一趟 pipeline，语义与逐条 getContent 一致；T2 起 miss 装载走批量 loader），按原序跳过 null
        List<ContentVO> contentVOList = new ArrayList<>();
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(db.pageIds());
        for (Long contentId : db.pageIds()) {
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

        return new PageResult<>(contentVOList, db.total(), page, pageSize);
    }

    /** 事务内 DB 查询结果（T3：事务回调的返回值载体，缓存读在事务外进行）。 */
    private record FeedDbData(List<Long> pageIds, int total) {
    }

}