package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.util.MyConnectionPool;

import java.sql.*;
import java.util.*;

@Component
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

    /**
     * 批量查询用户对指定内容列表的点赞状态
     * @return 已点赞的 contentId 集合
     */
    public Set<Long> findLikedContentIds(Connection conn, long userId, List<Long> contentIds) throws SQLException {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptySet();
        }
        StringBuilder sql = new StringBuilder("SELECT content_id FROM content_like WHERE user_id = ? AND content_id IN (");
        for (int i = 0; i < contentIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            pstmt.setLong(1, userId);
            for (int i = 0; i < contentIds.size(); i++) {
                pstmt.setLong(i + 2, contentIds.get(i));
            }
            Set<Long> result = new HashSet<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("content_id"));
                }
            }
            return result;
        }
    }

    /**
     * 查询用户所有点赞过的内容 ID（用于缓存全量加载）
     */
    public Set<Long> findAllLikedContentIds(Connection conn, long userId) throws SQLException {
        String sql = "SELECT content_id FROM content_like WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            Set<Long> result = new HashSet<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("content_id"));
                }
            }
            return result;
        }
    }

    /**
     * 查询某个内容的所有点赞者（content 为中心，用于缓存回填）
     * @return 所有点赞了该内容的 userId 集合
     */
    public Set<Long> findLikerIdsByContentId(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT user_id FROM content_like WHERE content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            Set<Long> result = new HashSet<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("user_id"));
                }
            }
            return result;
        }
    }

}
