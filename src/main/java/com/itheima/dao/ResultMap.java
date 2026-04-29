package com.itheima.dao;

import com.itheima.pojo.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class ResultMap {


    //封装视频/动态
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

    public static Content buildContent(ResultSet rs)throws SQLException{
        Content content=new Content();
        content.setId(rs.getLong("id"));
        content.setAuthorId(rs.getLong("user_id"));
        content.setType(rs.getInt("type"));
        content.setTitle(rs.getString("title"));
        content.setDescription(rs.getString("description"));
        content.setCategoryId(rs.getInt("category_id"));
        content.setCommentCount(rs.getInt("comment_count"));
        content.setLikeCount(rs.getInt("like_count"));
        content.setAuthorName(rs.getString("username"));
//        content.setCrateTime(rs.getTimestamp("create_time").toLocalDateTime());
        return content;
    }

    public static void buildContent(Map<Long,Content> contentMap, ResultSet rs)throws SQLException{
        Content content=new Content();
        long id= rs.getLong("id");
        content.setId(rs.getLong("id"));
        content.setAuthorId(rs.getLong("author_id"));
        content.setType(rs.getInt("type"));
        content.setTitle(rs.getString("title"));
        content.setDescription(rs.getString("description"));
        content.setCategoryId(rs.getInt("category_id"));
        content.setCommentCount(rs.getInt("comment_count"));
        content.setLikeCount(rs.getInt("like_count"));
        content.setAuthorName(rs.getString("username"));
//        content.setCrateTime(rs.getTimestamp("create_time").toLocalDateTime());
        contentMap.put(id,content);
    }
    public static Comment buildComment(ResultSet rs)throws SQLException{
        Comment cm=new Comment();
        cm.setUsername(rs.getString("username"));
        cm.setCommentId(rs.getLong("comment_id"));
        cm.setVideoId(rs.getLong("video_id"));
        cm.setUserId(rs.getLong("user_id"));
        cm.setContent(rs.getString("content"));
        if(rs.getObject("parent_id") == null){
            cm.setParentId(null);
        }
        else {
            cm.setParentId(rs.getLong("parent_id"));
        }
        cm.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        cm.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        cm.setLikeCount(rs.getInt("like_count"));
        cm.setIsDeleted(rs.getBoolean("is_deleted"));
        return cm;
    }

    public static User buildUserForProfile(ResultSet rs)throws SQLException{

        long id=rs.getLong("id");
        String userName=rs.getString("username");
        int followerCount=rs.getInt("follower_count");
        int followCount=rs.getInt("follow_count");
        return new User(id,userName,followCount,followerCount);
    }

    public static User buildUserForLogin(ResultSet rs)throws SQLException{
        long id=rs.getLong("id");
        String username=rs.getString("username");
        String hashedPassword=rs.getString("hashed_password");
        String phone=rs.getString("phone");
        return new User(id,hashedPassword,username,phone);
    }

    public static ContentMedia buildContentMedia(ResultSet rs ) throws SQLException {
        long mediaId=rs.getLong("id");
        long contentId=rs.getLong("content_id");
        String url=rs.getString("url");
        int type=rs.getInt("type");
        int sort=rs.getInt("sort");
        return new ContentMedia(mediaId,contentId,url,type,sort);

    }




}
