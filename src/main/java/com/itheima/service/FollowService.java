package com.itheima.service;

import com.itheima.dao.FollowDao;
import com.itheima.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.model.entity.User;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import com.itheima.util.LogUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FollowService {

    private final FollowDao followDao;
    private final UserDao userDao;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(FollowService.class);

    @InjectConstructor
    public FollowService(FollowDao followDao, UserDao userDao,
                         TransactionTemplate transactionTemplate) {
        this.followDao = followDao;
        this.userDao = userDao;
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
    }

    public List<Map<String, Object>> getFollowingList(long userId, Long currentUserId) {
        return transactionTemplate.execute(conn -> {
            try {
                List<Long> ids = followDao.getAllFollowedUserIds(conn, userId);
                return buildUserList(conn, ids, currentUserId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    public List<Map<String, Object>> getFollowerList(long userId, Long currentUserId) {
        return transactionTemplate.execute(conn -> {
            try {
                List<Long> ids = followDao.getFollowerUserIds(conn, userId);
                return buildUserList(conn, ids, currentUserId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    private List<Map<String, Object>> buildUserList(Connection conn, List<Long> ids, Long currentUserId) throws SQLException {
        if (ids.isEmpty()) return java.util.Collections.emptyList();
        List<User> users = userDao.findUsersByIds(conn, ids);
        Set<Long> followedSet = (currentUserId != null)
                ? followDao.getFollowedIds(conn, currentUserId, ids)
                : java.util.Collections.emptySet();

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (User u : users) {
            Map<String, Object> map = new java.util.HashMap<>();
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
    }
}
