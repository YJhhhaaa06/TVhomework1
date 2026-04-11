package com.itheima.dao;

import com.itheima.pojo.Comment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CommentDao {

    //增
    public static int addComment(Connection conn,long videoId,long userId,String content,Long parentId)throws SQLException {
        String sql = "insert into comment (video_id, user_id, content, parent_id) values (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, videoId);
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

    //从数据库根据评论ID删除评论
    public static int deleteComment(Connection conn,long commentId)throws SQLException{
        String sql="delete from comment where comment_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1,commentId);
            return pstmt.executeUpdate();
        }
    }

    //查
    public static List<Comment> findCommentsByVideo(Connection conn,long videoId)throws SQLException{
        String sql="select * from comment where video_id = ?";
        List<Comment> list=new ArrayList<Comment>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1,videoId);
            ResultSet rs= pstmt.executeQuery();
            while (rs.next()){
                Comment cm=ResultMap.mapResultToComment(rs);
            }
            return list;
        }
    }

    //没有改



}
