package com.itheima.content.service;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.user.model.entity.User;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.model.vo.ProfileVO;

import com.itheima.content.model.dto.PageResult;
import com.itheima.content.dao.ContentDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ProfileService {

    private final UserDao userDao;
    private final ContentDao contentDao;
    private final FollowCache followCache;
    private final ContentCache contentCache;
    private final LikeService likeService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER = LogUtil.getLogger(ProfileService.class);

    @InjectConstructor
    public ProfileService(UserDao userDao, ContentDao contentDao, FollowCache followCache,
                          ContentCache contentCache, LikeService likeService,
                          TransactionTemplate transactionTemplate) {
        this.userDao = userDao;
        this.contentDao = contentDao;
        this.followCache = followCache;
        this.contentCache = contentCache;
        this.likeService = likeService;
        this.transactionTemplate = transactionTemplate;
    }

    public ProfileVO getProfile(long profileUserId, Long currentUserId, int page, int pageSize) {
        // isFollowed 走关注缓存（FollowCache 三态读 + miss 回填 + Redis 挂降级 DB），在事务外读取
        Boolean isFollowed = (currentUserId != null && currentUserId != profileUserId)
                ? followCache.isFollowing(currentUserId, profileUserId)
                : null;
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
                // T8：页内批量读（一趟 pipeline，语义与逐条 getContent 一致），按原序跳过 null
                Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(pageIds);
                for (Long contentId : pageIds) {
                    ContentCacheDTO cached = byId.get(contentId);
                    if (cached == null) continue;
                    contentVOList.add(contentCache.toContentVO(cached));
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

}
