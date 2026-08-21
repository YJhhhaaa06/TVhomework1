package com.itheima.dao;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.ContentMedia;
import com.itheima.model.entity.User;

import java.time.LocalDateTime;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ResultMap {

    public static ContentCacheDTO buildContentCacheDTO(ResultSet rs) throws SQLException {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(rs.getLong("id"));
        dto.setAuthorId(rs.getLong("user_id"));
        dto.setType(rs.getInt("type"));
        dto.setTitle(rs.getString("title"));
        dto.setDescription(rs.getString("description"));
        dto.setCategoryId(rs.getInt("category_id"));
        dto.setCommentCount(rs.getInt("comment_count"));
        dto.setLikeCount(rs.getInt("like_count"));
        dto.setAuthorName(rs.getString("username"));
        dto.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        return dto;
    }

    public static CommentCacheDTO buildComment(ResultSet rs) throws SQLException {
        CommentCacheDTO cm = new CommentCacheDTO();
        cm.setUsername(rs.getString("username"));
        cm.setCommentId(rs.getLong("comment_id"));
        cm.setContentId(rs.getLong("content_id"));
        cm.setUserId(rs.getLong("user_id"));
        cm.setContent(rs.getString("content"));
        if (rs.getObject("parent_id") == null) {
            cm.setParentId(null);
        } else {
            cm.setParentId(rs.getLong("parent_id"));
        }
        cm.setLikeCount(rs.getInt("like_count"));
        return cm;
    }

    public static User buildUserForProfile(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String userName = rs.getString("username");
        int followerCount = rs.getInt("follower_count");
        int followCount = rs.getInt("follow_count");
        return new User(id, userName, followCount, followerCount);
    }

    public static User buildUserForLogin(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String username = rs.getString("username");
        String hashedPassword = rs.getString("hashed_password");
        String phone = rs.getString("phone");
        return new User(id, hashedPassword, username, phone);
    }

    public static ContentMedia buildContentMedia(ResultSet rs) throws SQLException {
        long mediaId = rs.getLong("id");
        long contentId = rs.getLong("content_id");
        String url = rs.getString("url");
        int type = rs.getInt("type");
        int sort = rs.getInt("sort");
        return new ContentMedia(mediaId, contentId, url, type, sort);
    }
}