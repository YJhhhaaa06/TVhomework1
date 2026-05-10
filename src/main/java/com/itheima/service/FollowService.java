package com.itheima.service;

import com.itheima.dao.FollowDao;
import com.itheima.dao.UserDao;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ServerException;
import com.itheima.factory.BeanFactory;
import com.itheima.pojo.User;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import com.itheima.util.LogUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FollowService {
    private FollowDao followDao = BeanFactory.getFollowDao();
    private UserDao userDao = BeanFactory.getUserDao();
    private static final Logger LOGGER =
            LogUtil.getLogger(FollowService.class);

    public void follow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException("不能关注自己");
        }
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            int rows = followDao.addFollow(conn, userId, followedUserId);
            if (rows == 0) {
                throw new ConflictException("已关注，不可重复操作");
            }
            userDao.updateFollowCount(conn, userId, 1);
            userDao.updateFollowerCount(conn, followedUserId, 1);
            conn.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "关注失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("关注失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<Map<String, Object>> getFollowingList(long userId, Long currentUserId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            List<Long> ids = followDao.getAllFollowedUserIds(conn, userId);
            return buildUserList(conn, ids, currentUserId);
        } catch (SQLException e) {
            throw new ServerException("查询失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<Map<String, Object>> getFollowerList(long userId, Long currentUserId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            List<Long> ids = followDao.getFollowerUserIds(conn, userId);
            return buildUserList(conn, ids, currentUserId);
        } catch (SQLException e) {
            throw new ServerException("查询失败");
        } finally {
            MyConnectionPool.release(conn);
        }
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
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            int rows = followDao.deleteFollow(conn, userId, followedUserId);
            if (rows == 0) {
                throw new ConflictException("未关注，不可取消");
            }
            userDao.updateFollowCount(conn, userId, -1);
            userDao.updateFollowerCount(conn, followedUserId, -1);
            conn.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "取关失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("取关失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }
}
