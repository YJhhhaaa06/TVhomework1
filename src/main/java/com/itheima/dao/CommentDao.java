package com.itheima.dao;

import com.itheima.pojo.CommentCacheDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CommentDao {

    //增
    public int addComment(Connection conn,long contentId,long userId,String content,Long parentId)throws SQLException {
        String sql = "insert into comment (content_id, user_id, content, parent_id) values (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            pstmt.setLong(2, userId);
            pstmt.setString(3, content);
            if (parentId == null) {
                //如果没有父评论，就设为BIGINT类型的NULL
                pstmt.setNull(4, java.sql.Types.BIGINT);
            } else {
                pstmt.setLong(4, parentId);
            }
            return pstmt.executeUpdate();
        }
    }

    //删
    //从数据库根据评论ID删除评论，管理员使用
    public int deleteCommentById(Connection conn,long commentId)throws SQLException{
        String sql="delete from comment where comment_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1,commentId);
            return pstmt.executeUpdate();
        }
    }
    //从数据库根据视频ID删除评论，用于删除视频
    public int deleteCommentByVideo(Connection conn,long contentId) throws SQLException {
        String sql="delete from comment where content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1,contentId);
            return pstmt.executeUpdate();
        }
    }

    //从数据库根据用户删除评论，用于注销账号
    public int deleteCommentByUser(Connection conn,long userId) throws SQLException {
        String sql="delete from comment where user_id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1,userId);
            return pstmt.executeUpdate();
        }
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

    public boolean isParentIdCorrect(Connection conn,long parentId,long contentId) throws SQLException {
        String sql="SELECT COUNT(*) " +
                "FROM comment " +
                "WHERE comment_id = ? " +
                "AND content_id = ? " +
                "  AND is_deleted = 0";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,parentId);
            pstmt.setLong(2,contentId);

            try (ResultSet rs= pstmt.executeQuery()){
                if (rs.next()) {
                    int count = rs.getInt(1);
                    // 数量>0：父评论存在且归属正确
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
    //关闭或开启评论区
    //隐藏视频下的评论
    public int hideCommentByContent(Connection conn, long contentId)throws SQLException{
        String sql="update comment set is_deleted =1 where content_id=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)) {
            pstmt.setLong(1,contentId);
            rows=pstmt.executeUpdate();
            return rows;
        }
    }

    //显示视频下的评论
    public int unhideCommentByContent(Connection conn, long ContentId)throws SQLException{
        String sql="update comment set is_deleted =0 where content_id=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)) {
            pstmt.setLong(1,ContentId);
            rows=pstmt.executeUpdate();
            return rows;
        }
    }

    public int getLikeCount(Connection conn, long commentId) throws SQLException {
        String sql = "SELECT like_count FROM comment WHERE comment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    public void updateLikeCount(Connection conn, Long commentId, int delta) throws SQLException {
        String sql = "UPDATE comment SET like_count = like_count + ? WHERE comment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, commentId);
            ps.executeUpdate();
        }
    }



}
