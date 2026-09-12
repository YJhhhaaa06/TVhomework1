package com.itheima.content.service;

import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContentStatusFiller {

    private final LikeService likeService;
    private final FollowCache followCache;

    @InjectConstructor
    public ContentStatusFiller(LikeService likeService, FollowCache followCache) {
        this.likeService = likeService;
        this.followCache = followCache;
    }

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

        // 批量 isFollowing（FollowCache 内部三态读 + miss 回填 + Redis 挂降级 DB，不抛出缓存异常）
        Map<Long, Boolean> followedMap = followCache.batchIsFollowing(userId, authorIds);
        for (ContentVO vo : list) {
            Boolean followed = followedMap.get(vo.getAuthorId());
            vo.setIsFollowed(followed != null && followed);
        }
    }

    public void fillFollowStatus(ContentDetailVO vo, Long userId) {
        if (userId == null || vo == null) return;

        vo.setIsFollowed(followCache.isFollowing(userId, vo.getAuthorId()));
    }
}