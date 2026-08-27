package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.model.cache.CommentCacheDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class CommentDao {

    //增
    public long addComment(Connection conn,long contentId,long userId,String content,Long parentId)throws SQLException {
        String sql = "insert into comment (content_id, user_id, content, parent_id) values (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, contentId);
            pstmt.setLong(2, userId);
            pstmt.setString(3, content);
            if (parentId == null) {
                pstmt.setNull(4, java.sql.Types.BIGINT);
            } else {
                pstmt.setLong(4, parentId);
            }
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            throw new SQLException("获取评论ID失败");
        }
    }

    public CommentCacheDTO findCommentById(Connection conn, long commentId) throws SQLException {
        String sql = "SELECT c.*, u.username FROM comment c LEFT JOIN users u ON c.user_id = u.id WHERE c.comment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildComment(rs);
                }
            }
        }
        return null;
    }



    //查
    public boolean isCommentExist(Connection conn,long commentId)throws SQLException {
        String sql = "SELECT COUNT(*) " +
                "FROM comment " +
                "WHERE comment_id = ? " +
                "  AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, commentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    // 数量>0：评论存在
                    return count > 0;
                }
            }
            return false;
        }
    }

    public  List<CommentCacheDTO> getComments(Connection conn, Long contentId) throws SQLException {

        String sql = "SELECT c.*, u.username " +
                "FROM comment c LEFT JOIN users u ON c.user_id = u.id " +
                "WHERE c.content_id=? AND c.is_deleted=0 ORDER BY c.comment_id";

        List<CommentCacheDTO> list = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);

            try(ResultSet rs = ps.executeQuery()){

            while (rs.next()) {
                CommentCacheDTO c = ResultMap.buildComment(rs);
                list.add(c);
            }
            }
        }

        return list;
    }



    //改

    public void updateLikeCount(Connection conn, Long commentId, int delta) throws SQLException {
        String sql = "UPDATE comment SET like_count = like_count + ? WHERE comment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, commentId);
            ps.executeUpdate();
        }
    }

    //删（软删除，均不可恢复；楼中楼规则：删主楼整栋楼、删回复只删自己）

    /** 删主楼：整栋楼软删（主楼 + 楼内回复） */
    public void softDeleteFloor(Connection conn, long mainCommentId) throws SQLException {
        String sql = "UPDATE comment SET is_deleted = 1 WHERE comment_id = ? OR parent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, mainCommentId);
            ps.setLong(2, mainCommentId);
            ps.executeUpdate();
        }
    }

    /** 删回复：仅软删自己 */
    public void softDeleteOne(Connection conn, long commentId) throws SQLException {
        String sql = "UPDATE comment SET is_deleted = 1 WHERE comment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            ps.executeUpdate();
        }
    }

    /** 统计主楼未删除的楼内回复数。
     *  口径：单删回复时 comment_count 已 -1；删主楼时再扣 "1(主楼) + 剩余未删回复数"，
     *  与递增口径对称，避免对已单删回复二次扣减导致负数。 */
    public int countFloorReplies(Connection conn, long mainCommentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM comment WHERE parent_id = ? AND is_deleted = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, mainCommentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        }
    }



}
