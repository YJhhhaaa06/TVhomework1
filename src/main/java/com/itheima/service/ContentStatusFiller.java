package com.itheima.service;

import com.itheima.dao.FollowDao;
import com.itheima.exception.DatabaseException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ContentStatusFiller {

    @Inject
    private LikeService likeService;
    @Inject
    private FollowDao followDao;
    @Inject
    private TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentStatusFiller.class);

    // ===== 点赞状态填充 =====

    public void fillContentLikeStatus(ContentDetailVO cdVO, long contentId, long userId) {
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, List.of(contentId));
        if (likedMap != null) {
            Boolean liked = likedMap.get(contentId);
            cdVO.setIsLiked(liked != null && liked);
        }
    }

    public void fillLikeAndFollowBatch(List<ContentVO> list, Long userId) {
        if (userId == null || list.isEmpty()) return;

        // 批量查点赞
        List<Long> contentIds = new ArrayList<>();
        for (ContentVO vo : list) {
            contentIds.add(vo.getId());
        }
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, contentIds);
        if (likedMap == null) likedMap = new HashMap<>();
        for (ContentVO vo : list) {
            Boolean liked = likedMap.get(vo.getId());
            vo.setIsLiked(liked != null && liked);
        }

        // 批量查关注
        fillFollowStatus(list, userId);
    }

    // ===== 关注状态填充 =====

    public void fillFollowStatus(List<ContentVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) return;

        List<Long> authorIds = new ArrayList<>();
        for (ContentVO vo : list) {
            if (vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) return;

        try {
            transactionTemplate.execute(conn -> {
                Set<Long> followedSet = followDao.getFollowedIds(conn, userId, authorIds);
                for (ContentVO vo : list) {
                    vo.setIsFollowed(followedSet.contains(vo.getAuthorId()));
                }
                return null;
            });
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "批量填充关注状态失败, userId=" + userId, e);
        }
    }

    public void fillFollowStatus(ContentDetailVO vo, Long userId) {
        if (userId == null || vo == null) return;

        try {
            transactionTemplate.execute(conn -> {
                Set<Long> followedSet = followDao.getFollowedIds(conn, userId, List.of(vo.getAuthorId()));
                vo.setIsFollowed(followedSet.contains(vo.getAuthorId()));
                return null;
            });
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "填充关注状态失败, userId=" + userId, e);
        }
    }
}
