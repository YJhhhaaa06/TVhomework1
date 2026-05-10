package com.itheima.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
