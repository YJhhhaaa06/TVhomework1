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

    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
        // 关注列表走关注缓存（FollowCache 内部三态读 + miss 回填 + Redis 挂降级 DB）
        List<Long> followedIds = followCache.getFollowingIds(currentUserId);

        if (followedIds.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
        }

        return transactionTemplate.execute(conn -> {
            try {
                int total = contentDao.countContentByUsers(conn, followedIds);
                if (total == 0) {
                    return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
                }

                int offset = (page - 1) * pageSize;
                List<Long> pageIds = contentDao.findContentIdsByUsers(conn, followedIds, offset, pageSize);

                List<ContentVO> contentVOList = new ArrayList<>();
                for (Long contentId : pageIds) {
                    ContentCacheDTO cached = contentCache.getContent(contentId);
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
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "获取关注动态失败, userId=" + currentUserId, e);
                throw new ServerException("获取关注动态失败");
            }
        });
    }

}