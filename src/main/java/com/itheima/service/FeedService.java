package com.itheima.service;

import com.itheima.model.dto.PageResult;
import com.itheima.dao.ContentDao;
import com.itheima.dao.FollowDao;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FeedService {

    private final FollowDao followDao;
    private final ContentDao contentDao;
    private final ContentCacheManager contentCacheManager;
    private final LikeService likeService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER = LogUtil.getLogger(FeedService.class);

    @InjectConstructor
    public FeedService(FollowDao followDao, ContentDao contentDao,
                       ContentCacheManager contentCacheManager, LikeService likeService,
                       TransactionTemplate transactionTemplate) {
        this.followDao = followDao;
        this.contentDao = contentDao;
        this.contentCacheManager = contentCacheManager;
        this.likeService = likeService;
        this.transactionTemplate = transactionTemplate;
    }

    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
        return transactionTemplate.execute(conn -> {
            try {
                List<Long> followedIds = followDao.getAllFollowedUserIds(conn, currentUserId);

                if (followedIds.isEmpty()) {
                    return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
                }

                int total = contentDao.countContentByUsers(conn, followedIds);
                if (total == 0) {
                    return new PageResult<>(Collections.emptyList(), 0, page, pageSize);
                }

                int offset = (page - 1) * pageSize;
                List<Long> pageIds = contentDao.findContentIdsByUsers(conn, followedIds, offset, pageSize);

                List<ContentVO> contentVOList = new ArrayList<>();
                for (Long contentId : pageIds) {
                    ContentCacheDTO cached = contentCacheManager.getContentFromCache(contentId);
                    if (cached == null) continue;
                    contentVOList.add(contentCacheManager.toContentVO(cached));
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
