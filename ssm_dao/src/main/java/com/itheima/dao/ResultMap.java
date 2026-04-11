package com.itheima.dao;

import com.itheima.pojo.Comment;
import com.itheima.pojo.Video;
import com.itheima.pojo.VideoResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class ResultMap {
    //封装视频,一次只封装一个
    public static Video mapResultToVideoInfo(ResultSet res) throws SQLException {
        long videoID = res.getLong("videoID");
        long uploadID = res.getLong("uploadID");
        String videoTitle = res.getString("videoTitle");
        String briefIntroduction = res.getString("briefIntroduction");
        return new Video(videoID, uploadID, videoTitle, briefIntroduction);
    }

    public static VideoResult mapResultToVideoResult(ResultSet rs) throws SQLException {
        VideoResult vr=new VideoResult();
        vr.setTitle(rs.getString("videoTitle"));
        vr.setIntro(rs.getString("briefIntroduction"));
        vr.setUrl(rs.getString("video_url"));
        vr.setAuthorName(rs.getString("username"));
        return vr;
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

}
