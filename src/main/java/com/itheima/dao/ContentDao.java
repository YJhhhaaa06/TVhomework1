package com.itheima.dao;

import com.itheima.pojo.ContentCacheDTO;
import com.itheima.util.MyConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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

    public int deleteContent(Connection conn, long contentId) throws SQLException {
        String sql = "delete from content where content_id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }

    public int hideContent(Connection conn, long contentId) throws SQLException {
        String sql = "UPDATE content SET is_deleted = 1 WHERE content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }

    public int unhideContent(Connection conn, long contentId) throws SQLException {
        String sql = "UPDATE content SET is_deleted = 0 WHERE content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
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

    public List<ContentCacheDTO> findAllContent() throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return findAllContent(conn);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentCacheDTO> findAllContentDetail() throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return findAllContentDetail(conn);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentCacheDTO> findAllContentDetail(Connection conn) throws SQLException {
        return findAllContent(conn);
    }

    public List<ContentCacheDTO> findContentByUser(Connection conn, long userId, int page, int pageSize) throws SQLException {
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
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE c.user_id=? AND is_deleted =0
               ORDER BY c.create_time DESC, c.id DESC
               LIMIT ?,?
               """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            int offset = (page - 1) * pageSize;
            pstmt.setInt(2, offset);
            pstmt.setInt(3, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(ResultMap.buildContentCacheDTO(rs));
                }
            }
        }
        return list;
    }

    public List<ContentCacheDTO> keywordSearchInDetail(Connection conn, String keyword, int page, int pageSize) throws SQLException {
        String sql = """
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.create_time,
                   u.username,
                   u.id AS user_id,
                   MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE) AS score
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE
                   MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE)
                   AND c.is_deleted = 0
               ORDER BY score DESC, c.create_time DESC
               LIMIT ?,?;
               """;
        List<ContentCacheDTO> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String likeKeyword = keyword.trim();
            pstmt.setString(1, likeKeyword);
            pstmt.setString(2, likeKeyword);
            int offset = (page - 1) * pageSize;
            pstmt.setInt(3, offset);
            pstmt.setInt(4, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(ResultMap.buildContentCacheDTO(rs));
                }
            }
        }
        return list;
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

    public int updateContentInfo(Connection conn, long contentId, String title, String description) throws SQLException {
        String sql = "update content set title=?,description=? where id=?";
        int rows;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, title);
            pstmt.setString(2, description);
            pstmt.setLong(3, contentId);
            rows = pstmt.executeUpdate();
        }
        return rows;
    }

    public int getLikeCount(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT like_count FROM content WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            try (ResultSet rs = ps.executeQuery()) {
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
}