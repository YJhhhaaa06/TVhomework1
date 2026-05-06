package com.itheima.dao;

import com.itheima.util.MyConnectionPool;

import java.sql.*;

public class ContentLikeDao {

    /**
     * 根据userId和contentId添加点赞记录
     * @param conn 数据库连接（由Service控制事务）
     * @param userId 点赞用户ID
     * @param contentId 内容ID
     * @return 受影响的行数（唯一键约束防止重复点赞，重复时返回0）
     */
    public int addLike(Connection conn, long userId, long contentId) throws SQLException {
        String sql = "INSERT INTO content_like (user_id, content_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, contentId);
            return pstmt.executeUpdate();
        }
    }

    /**
     * 根据userId和contentId删除点赞记录
     * @param conn 数据库连接（由Service控制事务）
     * @param userId 点赞用户ID
     * @param contentId 内容ID
     * @return 受影响的行数
     */
    public int deleteLike(Connection conn, long userId, long contentId) throws SQLException {
        String sql = "DELETE FROM content_like WHERE user_id = ? AND content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, contentId);
            return pstmt.executeUpdate();
        }
    }

    /**
     * 根据userId和contentId查看用户是否已经给content点过赞
     * @param userId 用户ID
     * @param contentId 内容ID
     * @return true表示已点赞，false表示未点赞
     */
    public boolean isLiked(long userId, long contentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content_like WHERE user_id = ? AND content_id = ?";
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return isLiked(conn,userId,contentId);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public boolean isLiked(Connection conn,long userId, long contentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content_like WHERE user_id = ? AND content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
                return false;
            }
        }

    }


}
