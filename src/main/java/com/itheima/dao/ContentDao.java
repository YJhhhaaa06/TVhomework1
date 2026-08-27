package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.model.cache.ContentCacheDTO;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ContentDao {

    public long addContent(Connection conn, long userId, int type, String title, String description, int categoryId) throws SQLException {
        String sql = "insert into content (user_id, title,type, description,category_id) values (?, ?, ?,?,?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, title);
            pstmt.setInt(3, type);
            pstmt.setString(4, description);
            pstmt.setInt(5, categoryId);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new SQLException("获取 contentID 失败");
                }
            }
        }
    }


    public boolean isContentExist(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content WHERE id = ? AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
            return false;
        }
    }

    public ContentCacheDTO findContent(Connection conn, long contentId) throws SQLException {
        String sql = """
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.comment_enabled,
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE c.id=? AND is_deleted =0
               """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildContentCacheDTO(rs);
                } else {
                    return null;
                }
            }
        }
    }

    public List<ContentCacheDTO> findAllContent(Connection conn) throws SQLException {
        List<ContentCacheDTO> list = new ArrayList<>();
        String sql = """
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.comment_enabled,
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE is_deleted =0
               ORDER BY c.create_time DESC, c.id DESC
               """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet res = pstmt.executeQuery()) {
            while (res.next()) {
                list.add(ResultMap.buildContentCacheDTO(res));
            }
        }
        return list;
    }

    // 查询全部未删除内容 id（运维扫描用）
    public List<Long> findAllContentIds(Connection conn) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "select id from content where is_deleted = 0 order by id";
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    // 更新内容的媒体完整性聚合状态
    public int updateFileExists(Connection conn, long contentId, boolean exists, Timestamp lastVerifyTime) throws SQLException {
        String sql = "update content set file_exists=?, last_verify_time=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, exists ? 1 : 0);
            pstmt.setTimestamp(2, lastVerifyTime);
            pstmt.setLong(3, contentId);
            return pstmt.executeUpdate();
        }
    }



    public List<Long> findContentIdsByUser(Connection conn, long userId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT c.id FROM content c WHERE c.user_id = ? AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    public List<Long> findContentIdsByUsers(Connection conn, List<Long> userIds, int offset, int pageSize) throws SQLException {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT c.id FROM content c WHERE c.user_id IN (");
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(") AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC LIMIT ?, ?");

        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < userIds.size(); i++) {
                pstmt.setLong(i + 1, userIds.get(i));
            }
            pstmt.setInt(userIds.size() + 1, offset);
            pstmt.setInt(userIds.size() + 2, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    public int countContentByUsers(Connection conn, List<Long> userIds) throws SQLException {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM content WHERE user_id IN (");
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(") AND is_deleted = 0");

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < userIds.size(); i++) {
                pstmt.setLong(i + 1, userIds.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    public int countContentByUser(Connection conn, long userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content WHERE user_id = ? AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }



    public List<Long> keywordSearchInBrief(Connection conn, String keyword, int page, int pageSize) throws SQLException {
        String kw = keyword.trim();
        String sql;
        boolean singleChar = kw.length() == 1;
        if (singleChar) {
            sql = "SELECT c.id FROM content c WHERE c.title LIKE ? AND c.is_deleted = 0 ORDER BY c.create_time DESC LIMIT ?,?";
        } else {
            sql = """
                    SELECT c.id FROM content c
                    WHERE MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE)
                      AND c.is_deleted = 0
                    ORDER BY c.create_time DESC
                    LIMIT ?,?
                    """;
        }
        List<Long> contentIdList = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (singleChar) {
                pstmt.setString(1, "%" + kw + "%");
            } else {
                pstmt.setString(1, kw);
            }
            int offset = (page - 1) * pageSize;
            pstmt.setInt(2, offset);
            pstmt.setInt(3, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    contentIdList.add(rs.getLong(1));
                }
                return contentIdList;
            }
        }
    }

    public int countKeywordSearch(Connection conn, String keyword) throws SQLException {
        String kw = keyword.trim();
        String sql;
        boolean singleChar = kw.length() == 1;
        if (singleChar) {
            sql = "SELECT COUNT(*) FROM content c WHERE c.title LIKE ? AND c.is_deleted = 0";
        } else {
            sql = "SELECT COUNT(*) FROM content c WHERE MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE) AND c.is_deleted = 0";
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (singleChar) {
                pstmt.setString(1, "%" + kw + "%");
            } else {
                pstmt.setString(1, kw);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }



    public void updateLikeCount(Connection conn, Long contentId, int delta) throws SQLException {
        String sql = "UPDATE content SET like_count = like_count + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, contentId);
            ps.executeUpdate();
        }
    }

    public void updateCommentCount(Connection conn, Long contentId, int delta) throws SQLException {
        String sql = "UPDATE content SET comment_count = comment_count + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, contentId);
            ps.executeUpdate();
        }
    }

    /** 作者开关评论区（1-开, 0-关） */
    public int updateCommentEnabled(Connection conn, long contentId, boolean enabled) throws SQLException {
        String sql = "UPDATE content SET comment_enabled = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, contentId);
            return ps.executeUpdate();
        }
    }
}
