package com.itheima.comment.dao;

import com.itheima.dao.ResultMap;
import com.itheima.ioc.annotation.Component;
import com.itheima.content.model.cache.CommentCacheDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class CommentDao {

    //增
    public long addComment(Connection conn, long contentId, long userId, String content, Long parentId, Long replyToUserId) throws SQLException {
        String sql = "insert into comment (content_id, user_id, content, parent_id, reply_to_user_id) values (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, contentId);
            pstmt.setLong(2, userId);
            pstmt.setString(3, content);
            if (parentId == null) {
                pstmt.setNull(4, java.sql.Types.BIGINT);
            } else {
                pstmt.setLong(4, parentId);
            }
            if (replyToUserId == null) {
                pstmt.setNull(5, java.sql.Types.BIGINT);
            } else {
                pstmt.setLong(5, replyToUserId);
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
        String sql = "SELECT c.*, u.username, r.username AS reply_to_username FROM comment c " +
                "LEFT JOIN users u ON c.user_id = u.id " +
                "LEFT JOIN users r ON c.reply_to_user_id = r.id WHERE c.comment_id = ?";
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
    /** 评论点赞缓存失效定位用：查评论所属内容 id；不存在/已删除返回 null */
    public Long getContentIdByCommentId(Connection conn, long commentId) throws SQLException {
        String sql = "SELECT content_id FROM comment WHERE comment_id = ? AND is_deleted = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return null;
    }

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

        String sql = "SELECT c.*, u.username, r.username AS reply_to_username " +
                "FROM comment c LEFT JOIN users u ON c.user_id = u.id " +
                "LEFT JOIN users r ON c.reply_to_user_id = r.id " +
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

    //查（T10-A 两键组 + 主楼窗口装载：keyset 窗口 / 主楼计数 / 楼中楼按主楼分组 / 评论→主楼定位）

    /**
     * 主楼窗口查询（keyset）：{@code comment_id > afterCommentId} 升序取前 limit 条主楼。
     *
     * <p>{@code afterCommentId=0} 表示从头取（首个窗口）。对应主楼 List 缓存的水位不足追加装载；
     * {@code (content_id, parent_id)} 索引（T10-A DDL）服务本查询（等同扫描 icon AS 主楼区间）。
     */
    public List<CommentCacheDTO> getMainCommentsAfter(Connection conn, long contentId, long afterCommentId, int limit) throws SQLException {
        String sql = "SELECT c.*, u.username, r.username AS reply_to_username " +
                "FROM comment c LEFT JOIN users u ON c.user_id = u.id " +
                "LEFT JOIN users r ON c.reply_to_user_id = r.id " +
                "WHERE c.content_id=? AND c.parent_id IS NULL AND c.is_deleted=0 " +
                "  AND c.comment_id > ? ORDER BY c.comment_id LIMIT ?";
        List<CommentCacheDTO> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            ps.setLong(2, afterCommentId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(ResultMap.buildComment(rs));
                }
            }
        }
        return list;
    }

    /** 主楼条数（T10-A 真实 total 来源：窗口装载首装时惰性 COUNT 一次写 count key）。 */
    public int countMainComments(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM comment WHERE content_id=? AND parent_id IS NULL AND is_deleted=0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * 楼中楼按主楼批量取：{@code parent_id IN (rootIds)} 升序（含 JOIN username/reply_to_username）。
     * 返回全量行（含多个主楼），调用方按 {@code parent_id} 分组。
     */
    public List<CommentCacheDTO> getRepliesByRootIds(Connection conn, long contentId, List<Long> rootIds) throws SQLException {
        if (rootIds == null || rootIds.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT c.*, u.username, r.username AS reply_to_username " +
                "FROM comment c LEFT JOIN users u ON c.user_id = u.id " +
                "LEFT JOIN users r ON c.reply_to_user_id = r.id " +
                "WHERE c.content_id=? AND c.parent_id IN (");
        for (int i = 0; i < rootIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") AND c.is_deleted=0 ORDER BY c.comment_id");
        List<CommentCacheDTO> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, contentId);
            for (int i = 0; i < rootIds.size(); i++) {
                ps.setLong(i + 2, rootIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(ResultMap.buildComment(rs));
                }
            }
        }
        return list;
    }

    /**
     * 评论 → 所属主楼 id：沿 {@code parent_id} 链上溯到顶（防御存量多级链 seed）。
     * 主楼自身返回自身 id；链断（父已软删/不存在）返回 null（调用方按"无法定位"整组失效兜底）。
     */
    public Long getRootIdByCommentId(Connection conn, long commentId) throws SQLException {
        long cursor = commentId;
        int hops = 0;
        while (hops++ < 32) { // 防御异常长链（理论上层深极小，32 封顶防环）
            String sql = "SELECT parent_id FROM comment WHERE comment_id=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, cursor);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null; // 该评论已不存在（含软删）→ 链断
                    }
                    long parentId = rs.getLong("parent_id");
                    if (rs.wasNull() || parentId == 0) {
                        return cursor; // 已到主楼
                    }
                    cursor = parentId;
                }
            }
        }
        return null; // 异常长链/环，放弃（调用方整组失效兜底）
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

    /** 删除内容时级联软删该内容全部评论（A1，一次覆盖主楼与楼内回复；对已单删评论幂等） */
    public int softDeleteByContentId(Connection conn, long contentId) throws SQLException {
        String sql = "UPDATE comment SET is_deleted = 1 WHERE content_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            return ps.executeUpdate();
        }
    }



}
