package com.itheima.service;

import com.itheima.dao.FollowDao;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ServerException;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;

public class FollowService {
    private FollowDao followDao = new FollowDao();

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
            conn.commit();
        } catch (SQLException e) {
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
            conn.commit();
        } catch (SQLException e) {
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
