package com.itheima.dao;


import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
@Component
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

    /**
     * 批量查询用户对指定评论列表的点赞状态
     * @return 已点赞的 commentId 集合
     */
    public Set<Long> findLikedCommentIds(Connection conn, long userId, List<Long> commentIds) throws SQLException {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptySet();
        }
        StringBuilder sql = new StringBuilder("SELECT comment_id FROM comment_like WHERE user_id = ? AND comment_id IN (");
        for (int i = 0; i < commentIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            pstmt.setLong(1, userId);
            for (int i = 0; i < commentIds.size(); i++) {
                pstmt.setLong(i + 2, commentIds.get(i));
            }
            Set<Long> result = new HashSet<>();
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("comment_id"));
                }
            }
            return result;
        }
    }

    /**
     * 查询某条评论的所有点赞者（comment 为中心，用于缓存回填）
     * @return 所有点赞了该评论的 userId 集合
     */
    public Set<Long> findLikerIdsByCommentId(Connection conn, long commentId) throws SQLException {
        String sql = "SELECT user_id FROM comment_like WHERE comment_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, commentId);
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
