package com.itheima.follow.service;

import com.itheima.follow.dao.FollowDao;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import com.itheima.util.LogUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FollowService {

    private final FollowDao followDao;
    private final UserDao userDao;
    private final FollowCache followCache;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(FollowService.class);

    @InjectConstructor
    public FollowService(FollowDao followDao, UserDao userDao, FollowCache followCache,
                         TransactionTemplate transactionTemplate) {
        this.followDao = followDao;
        this.userDao = userDao;
        this.followCache = followCache;
        this.transactionTemplate = transactionTemplate;
    }

    public void follow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException("不能关注自己");
        }
        transactionTemplate.execute(conn -> {
            // 先检查是否已关注
            boolean alreadyFollowed = followDao.isFollowing(conn, userId, followedUserId);
            if (alreadyFollowed) {
                throw new ConflictException("已关注，不可重复操作");
            }
            try {
                followDao.addFollow(conn, userId, followedUserId);
                userDao.updateFollowCount(conn, userId, 1);
                userDao.updateFollowerCount(conn, followedUserId, 1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "关注失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
                throw new ServerException("关注失败");
            }
        });
        // DB 提交后缓存双写（NEEDS 4.10：MULTI 原子，失败双 DEL 自愈，不影响主流程）
        followCache.cacheFollow(userId, followedUserId);
    }

    public List<Map<String, Object>> getFollowingList(long userId, Long currentUserId) {
        List<Long> ids = followCache.getFollowingIds(userId);
        if (ids.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return transactionTemplate.execute(conn -> {
            try {
                return buildUserList(conn, ids, currentUserId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    public List<Map<String, Object>> getFollowerList(long userId, Long currentUserId) {
        List<Long> ids = followCache.getFollowerIds(userId);
        if (ids.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return transactionTemplate.execute(conn -> {
            try {
                return buildUserList(conn, ids, currentUserId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    private List<Map<String, Object>> buildUserList(Connection conn, List<Long> ids, Long currentUserId) throws SQLException {
        if (ids.isEmpty()) return java.util.Collections.emptyList();
        List<User> users = userDao.findUsersByIds(conn, ids);
        Set<Long> followedSet = new HashSet<>();
        if (currentUserId != null) {
            Map<Long, Boolean> followedMap = followCache.batchIsFollowing(currentUserId, ids);
            for (Map.Entry<Long, Boolean> entry : followedMap.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    followedSet.add(entry.getKey());
                }
            }
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (User u : users) {
            Map<String, Object> map = new HashMap<>();
            map.put("userId", u.getId());
            map.put("username", u.getUserName());
            map.put("isFollowed", followedSet.contains(u.getId()));
            map.put("isSelf", currentUserId != null && u.getId() == currentUserId.longValue());
            result.add(map);
        }
        return result;
    }

    public void unfollow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException( "不能取关自己");
        }
        transactionTemplate.execute(conn -> {
            // 先检查是否已关注
            boolean isFollowing = followDao.isFollowing(conn, userId, followedUserId);
            if (!isFollowing) {
                throw new ConflictException("未关注，不可取消");
            }
            try {
                followDao.deleteFollow(conn, userId, followedUserId);
                userDao.updateFollowCount(conn, userId, -1);
                userDao.updateFollowerCount(conn, followedUserId, -1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "取关失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
                throw new ServerException("取关失败");
            }
        });
        // DB 提交后缓存双写（SREM，失败双 DEL 自愈，不影响主流程）
        followCache.cacheUnfollow(userId, followedUserId);
    }
}