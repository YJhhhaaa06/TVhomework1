package com.itheima.service;

import com.itheima.model.dto.PageResult;
import com.itheima.dao.ContentDao;
import com.itheima.dao.FollowDao;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FeedService {

    @Inject
    private FollowDao followDao;
    @Inject
    private ContentDao contentDao;
    @Inject
    private ContentService contentService;
    @Inject
    private LikeService likeService;
    private static final Logger LOGGER = LogUtil.getLogger(FeedService.class);

    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
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
                ContentCacheDTO cached = contentService.getContentFromCache(contentId);
                if (cached == null) continue;
                ContentVO cVO = new ContentVO();
                copyToContentVO(cVO, cached);
                contentVOList.add(cVO);
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
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void copyToContentVO(ContentVO cVO, ContentCacheDTO dto) {
        cVO.setId(dto.getId());
        cVO.setAuthorId(dto.getAuthorId());
        cVO.setType(dto.getType());
        cVO.setTitle(dto.getTitle());
        cVO.setDescription(dto.getDescription());
        cVO.setCategoryId(dto.getCategoryId());
        cVO.setCommentCount(dto.getCommentCount());
        cVO.setLikeCount(dto.getLikeCount());
        cVO.setAuthorName(dto.getAuthorName());
        cVO.setCoverUrl(dto.getCoverUrl());
        cVO.setCreateTime(dto.getCreateTime());
    }
}
