package com.itheima.service;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.User;
import com.itheima.model.vo.ContentVO;
import com.itheima.model.vo.ProfileVO;

import com.itheima.model.dto.PageResult;
import com.itheima.dao.ContentDao;
import com.itheima.dao.FollowDao;
import com.itheima.dao.UserDao;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ProfileService {

    @Inject
    private UserDao userDao;
    @Inject
    private ContentDao contentDao;
    @Inject
    private FollowDao followDao;
    @Inject
    private ContentService contentService;
    @Inject
    private LikeService likeService;
    @Inject
    private TransactionTemplate transactionTemplate;
    private static final Logger LOGGER = LogUtil.getLogger(ProfileService.class);

    public ProfileVO getProfile(long profileUserId, Long currentUserId, int page, int pageSize) {
        return transactionTemplate.execute(conn -> {
            try {
                User user = userDao.getUserForProfileById(conn, profileUserId);
                if (user == null) {
                    throw new NotFoundException("用户不存在");
                }

                List<Long> allIds = contentDao.findContentIdsByUser(conn, profileUserId);
                int total = allIds.size();
                int offset = (page - 1) * pageSize;
                int end = Math.min(offset + pageSize, total);
                List<Long> pageIds = offset < total ? allIds.subList(offset, end) : Collections.emptyList();

                List<ContentVO> contentVOList = new ArrayList<>();
                for (Long contentId : pageIds) {
                    ContentCacheDTO cached = contentService.getContentFromCache(contentId);
                    if (cached == null) continue;
                    ContentVO cVO = new ContentVO();
                    copyToContentVO(cVO, cached);
                    contentVOList.add(cVO);
                }

                Boolean isFollowed = null;
                if (currentUserId != null && currentUserId != profileUserId) {
                    Set<Long> followedSet = followDao.getFollowedIds(conn, currentUserId, List.of(profileUserId));
                    isFollowed = followedSet.contains(profileUserId);
                }

                if (currentUserId != null && !contentVOList.isEmpty()) {
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

                PageResult<ContentVO> pageResult = new PageResult<>(contentVOList, total, page, pageSize);
                return new ProfileVO(user.getId(), user.getUserName(),
                        user.getFollowerCount(), user.getFollowCount(), isFollowed, pageResult);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "获取用户主页失败, profileUserId=" + profileUserId, e);
                throw new ServerException("获取用户主页失败");
            }
        });
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
