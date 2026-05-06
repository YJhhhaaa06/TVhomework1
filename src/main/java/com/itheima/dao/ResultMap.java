package com.itheima.dao;

import com.itheima.pojo.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class ResultMap {


    //封装视频/动态
    public static RecommendVO buildRecommendVO(ResultSet rs) throws SQLException {
        RecommendVO recommendVO =new RecommendVO();
        recommendVO.setId(rs.getLong("id"));
        recommendVO.setAuthorId(rs.getLong("user_id"));
        recommendVO.setType(rs.getInt("type"));
        recommendVO.setTitle(rs.getString("title"));
        recommendVO.setDescription(rs.getString("description"));
        recommendVO.setCategoryId(rs.getInt("category_id"));
        recommendVO.setCommentCount(rs.getInt("comment_count"));
        recommendVO.setLikeCount(rs.getInt("like_count"));
        recommendVO.setAuthorName(rs.getString("username"));
//        content.setCrateTime(rs.getTimestamp("create_time").toLocalDateTime());
        return recommendVO;
    }

    public static ContentDetailVO buildContent(ResultSet rs)throws SQLException{
        ContentDetailVO contentDetailVO =new ContentDetailVO();
        contentDetailVO.setId(rs.getLong("id"));
        contentDetailVO.setAuthorId(rs.getLong("user_id"));
        contentDetailVO.setType(rs.getInt("type"));
        contentDetailVO.setTitle(rs.getString("title"));
        contentDetailVO.setDescription(rs.getString("description"));
        contentDetailVO.setCategoryId(rs.getInt("category_id"));
        contentDetailVO.setCommentCount(rs.getInt("comment_count"));
        contentDetailVO.setLikeCount(rs.getInt("like_count"));
        contentDetailVO.setAuthorName(rs.getString("username"));
//        content.setCrateTime(rs.getTimestamp("create_time").toLocalDateTime());
        return contentDetailVO;
    }

    public static void buildContent(Map<Long, ContentDetailVO> contentMap, ResultSet rs)throws SQLException{
        ContentDetailVO contentDetailVO =new ContentDetailVO();
        long id= rs.getLong("id");
        contentDetailVO.setId(rs.getLong("id"));
        contentDetailVO.setAuthorId(rs.getLong("author_id"));
        contentDetailVO.setType(rs.getInt("type"));
        contentDetailVO.setTitle(rs.getString("title"));
        contentDetailVO.setDescription(rs.getString("description"));
        contentDetailVO.setCategoryId(rs.getInt("category_id"));
        contentDetailVO.setCommentCount(rs.getInt("comment_count"));
        contentDetailVO.setLikeCount(rs.getInt("like_count"));
        contentDetailVO.setAuthorName(rs.getString("username"));
//        content.setCrateTime(rs.getTimestamp("create_time").toLocalDateTime());
        contentMap.put(id, contentDetailVO);
    }
    public static CommentVO buildComment(ResultSet rs)throws SQLException{
        CommentVO cm=new CommentVO();
        cm.setUsername(rs.getString("username"));
        cm.setCommentId(rs.getLong("comment_id"));
        cm.setContentId(rs.getLong("content_id"));
        cm.setUserId(rs.getLong("user_id"));
        cm.setContent(rs.getString("content"));
        if(rs.getObject("parent_id") == null){
            cm.setParentId(null);
        }
        else {
            cm.setParentId(rs.getLong("parent_id"));
        }
//        cm.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
//        cm.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
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
