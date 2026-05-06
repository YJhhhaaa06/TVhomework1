package com.itheima.dao;


import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;



import com.itheima.util.MyConnectionPool;

import java.sql.*;
public class CommentLikeDao {

    /**
     * 根据userId和commentId添加点赞记录
     * @param conn 数据库连接（由Service控制事务）
     * @param userId 点赞用户ID
     * @param commentId 评论ID
     * @return 受影响的行数（唯一键约束防止重复点赞，重复时返回0）
     */
    public int addLike(Connection conn, long userId, long commentId) throws SQLException {
        String sql = "INSERT INTO comment_like (user_id, comment_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, commentId);
            return pstmt.executeUpdate();
        }
    }

    /**
     * 根据userId和commentId删除点赞记录
     * @param conn 数据库连接（由Service控制事务）
     * @param userId 点赞用户ID
     * @param commentId 评论ID
     * @return 受影响的行数
     */
    public int removeLike(Connection conn, long userId, long commentId) throws SQLException {
        String sql = "DELETE FROM comment_like WHERE user_id = ? AND comment_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, commentId);
            return pstmt.executeUpdate();
        }
    }


    /**
     * 根据userId和commentId查看用户是否已经给评论点过赞
     * @param userId 用户ID
     * @param commentId 评论ID
     * @return true表示已点赞，false表示未点赞
     */
    public boolean isLiked(long userId, long commentId) throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return isLiked(conn,userId,commentId);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public boolean isLiked(Connection conn,long userId, long commentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM comment_like WHERE user_id = ? AND comment_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, commentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
                return false;
            }
        }

    }
}
