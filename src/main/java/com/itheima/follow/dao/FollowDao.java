package com.itheima.follow.dao;

import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FollowDao {

    // 批量查询：userId 关注了 authorIds 中的哪些人
    public Set<Long> getFollowedIds(Connection conn, long userId, List<Long> authorIds) throws SQLException {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptySet();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT followed_user_id FROM follow WHERE user_id = ? AND followed_user_id IN (");
        for (int i = 0; i < authorIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");

        Set<Long> result = new HashSet<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            pstmt.setLong(1, userId);
            for (int i = 0; i < authorIds.size(); i++) {
                pstmt.setLong(i + 2, authorIds.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("followed_user_id"));
                }
            }
        }
        return result;
    }

    // 获取用户关注的所有博主ID
    public List<Long> getAllFollowedUserIds(Connection conn, long userId) throws SQLException {
        List<Long> result = new ArrayList<>();
        String sql = "SELECT followed_user_id FROM follow WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("followed_user_id"));
                }
            }
        }
        return result;
    }

    // 获取用户的所有粉丝ID
    public List<Long> getFollowerUserIds(Connection conn, long userId) throws SQLException {
        List<Long> result = new ArrayList<>();
        String sql = "SELECT user_id FROM follow WHERE followed_user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("user_id"));
                }
            }
        }
        return result;
    }

    // 关注方向窗口查询（T11-C-1 新增）：按 followed_user_id 升序取 [offset, offset+count)。
    // 走既有 uk_user_follow(user_id, followed_user_id)——等值列 user_id + 有序第二列，
    // 无需 filesort；与缓存侧 ZSet 的 score=成员 id 升序口径同源。
    public List<Long> getFollowedUserIdsInWindow(Connection conn, long userId, long offset, int count)
            throws SQLException {
        if (count <= 0 || offset < 0) {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>();
        String sql = "SELECT followed_user_id FROM follow WHERE user_id = ? "
                + "ORDER BY followed_user_id LIMIT ? OFFSET ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, count);
            pstmt.setLong(3, offset);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("followed_user_id"));
                }
            }
        }
        return result;
    }

    // 粉丝方向窗口查询（T11-C-1 新增）：按 user_id 升序取 [offset, offset+count)。
    // 依赖本任务 G9 落地的 idx_followed_user_user(followed_user_id, user_id)：等值列 + 有序
    // 第二列同序，避免用 idx_followed_user_id 时的 filesort。
    public List<Long> getFollowerUserIdsInWindow(Connection conn, long followedUserId, long offset, int count)
            throws SQLException {
        if (count <= 0 || offset < 0) {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>();
        String sql = "SELECT user_id FROM follow WHERE followed_user_id = ? "
                + "ORDER BY user_id LIMIT ? OFFSET ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, followedUserId);
            pstmt.setInt(2, count);
            pstmt.setLong(3, offset);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("user_id"));
                }
            }
        }
        return result;
    }

    // 检查是否已关注
    public boolean isFollowing(Connection conn, long userId, long followedUserId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM follow WHERE user_id = ? AND followed_user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, followedUserId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // 关注
    public int addFollow(Connection conn, long userId, long followedUserId) throws SQLException {
        String sql = "INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, followedUserId);
            return pstmt.executeUpdate();
        }
    }

    // 取关
    public int deleteFollow(Connection conn, long userId, long followedUserId) throws SQLException {
        String sql = "DELETE FROM follow WHERE user_id = ? AND followed_user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, followedUserId);
            return pstmt.executeUpdate();
        }
    }
}
