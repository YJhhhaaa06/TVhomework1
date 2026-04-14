package com.itheima.dao;

import com.itheima.pojo.Comment;
import com.itheima.pojo.User;
import com.itheima.pojo.Video;
import com.itheima.pojo.VideoDetail;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ResultMap {
    //封装视频,一次只封装一个
    public static Video mapResultToVideoInfo(ResultSet res) throws SQLException {
        long videoID = res.getLong("videoID");
        long uploadID = res.getLong("uploadID");
        String videoTitle = res.getString("videoTitle");
        String briefIntroduction = res.getString("briefIntroduction");
        return new Video(videoID, uploadID, videoTitle, briefIntroduction);
    }

    public static VideoDetail mapResultToVideoDetail(ResultSet rs) throws SQLException {
        VideoDetail vd=new VideoDetail();
        vd.setTitle(rs.getString("videoTitle"));
        vd.setIntro(rs.getString("briefIntroduction"));
        vd.setUrl(rs.getString("video_url"));
        vd.setAuthorName(rs.getString("username"));
        vd.setUploadID(rs.getLong("id"));
        vd.setVideoId(rs.getLong("videoID"));
        return vd;
    }
    public static Comment mapResultToComment(ResultSet rs)throws SQLException{
        Comment cm=new Comment();
        cm.setCommentId(rs.getLong("comment_id"));
        cm.setVideoId(rs.getLong("video_id"));
        cm.setUserId(rs.getLong("user_id"));
        cm.setContent(rs.getString("content"));
        cm.setParentId(rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"));
        cm.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        cm.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        cm.setLikeCount(rs.getInt("like_count"));
        cm.setIsDeleted(rs.getBoolean("is_deleted"));
        return cm;
    }

    public static User mapResultToUser(ResultSet rs)throws SQLException{

        long id=rs.getLong("id");
        String userName=rs.getString("username");
        String hashedPassword=rs.getString("hashedPassword");
        int followerCount=rs.getInt("follower_count");
        int followCount=rs.getInt("follow_count");
        String phone=rs.getString("phone");
        return new User(id,userName,hashedPassword,followerCount,followCount,phone);
    }
//    public static VideoDetail mapComment(VideoDetail vd,Comment comment){
//
//        vd.commentList.add(comment);
//    }

}
