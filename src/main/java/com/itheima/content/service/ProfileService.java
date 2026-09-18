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

    /**
     * 用户主页。第五期 T3（治 N2）：**缓存批量读移出 DB 事务**——事务回调只承载 DB 查询
     * （profile 用户行 + 该作者全部内容 id + 页内切片），提交归还连接后，再在**事务外**读
     * {@code contentCache.getContentsBatch}（含 miss 装载）与 {@code likeService.batchIsContentLiked}
     * ——消除"外层事务持连接 + miss 装载再取新连接"的叠加（自研 {@link TransactionTemplate}
     * 无传播语义，嵌套读各自取新连接，连接池 20 上限下高峰会互相等连接），对齐关注/粉丝
     * 计数读"事务外"口径。对外行为零变化（返回集/顺序/跳过 null/异常语义一概不变）。
     */
    public ProfileVO getProfile(long profileUserId, Long currentUserId, int page, int pageSize) {
        // isFollowed 走关注缓存（FollowCache 三态读 + miss 回填 + Redis 挂降级 DB），在事务外读取
        Boolean isFollowed = (currentUserId != null && currentUserId != profileUserId)
                ? followCache.isFollowing(currentUserId, profileUserId)
                : null;
        // 关注数/粉丝数计数走独立计数 key（第四期 T6 R-01：Cache-Aside，DB 为最终真理），在事务外读取
        int followerCount = followCache.getFollowerCount(profileUserId);
        int followCount = followCache.getFollowCount(profileUserId);

        // T3：事务回调只做 DB 查询，不触碰任何缓存（缓存读见下方事务外段）
        ProfileDbData db = transactionTemplate.execute(conn -> {
            try {
                User user = userDao.getUserForProfileById(conn, profileUserId);
                if (user == null) {
                    throw new NotFoundException("用户不存在");
                }

                List<Long> allIds = contentDao.findContentIdsByUser(conn, profileUserId);
                int total = allIds.size();
                int offset = (page - 1) * pageSize;
                int end = Math.min(offset + pageSize, total);
                List<Long> pageIds = offset < total
                        ? new ArrayList<>(allIds.subList(offset, end))
                        : Collections.emptyList();
                return new ProfileDbData(user, pageIds, total);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "获取用户主页失败, profileUserId=" + profileUserId, e);
                throw new ServerException("获取用户主页失败");
            }
        });

        // 事务外：页内批量读（T8 一趟 pipeline，语义与逐条 getContent 一致；T2 起 miss 装载走批量 loader），按原序跳过 null
        List<ContentVO> contentVOList = new ArrayList<>();
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(db.pageIds());
        for (Long contentId : db.pageIds()) {
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

        PageResult<ContentVO> pageResult = new PageResult<>(contentVOList, db.total(), page, pageSize);
        return new ProfileVO(db.user().getId(), db.user().getUserName(),
                followerCount, followCount, isFollowed, pageResult);
    }

    /** 事务内 DB 查询结果（T3：事务回调的返回值载体，缓存读在事务外进行）。 */
    private record ProfileDbData(User user, List<Long> pageIds, int total) {
    }

}
