package com.itheima.factory;

import com.itheima.dao.*;
import com.itheima.service.*;

public class BeanFactory {
    //DAO层对象
    private final static CommentDao COMMENT_DAO = new CommentDao();
    private final static CommentLikeDao COMMENT_LIKE_DAO = new CommentLikeDao();
    private final static CommentMediaDao COMMENT_MEDIA_DAO = new CommentMediaDao();
    private final static ContentDao CONTENT_DAO = new ContentDao();
    private final static ContentLikeDao CONTENT_LIKE_DAO = new ContentLikeDao();
    private final static ContentMediaDao CONTENT_MEDIA_DAO = new ContentMediaDao();
    private final static FollowDao FOLLOW_DAO = new FollowDao();
    private final static UserDao USER_DAO = new UserDao();

    //Service层对象（按依赖拓扑顺序声明）
    private final static LikeCacheService LIKE_CACHE_SERVICE = new LikeCacheService();
    private final static FileUploadService FILE_UPLOAD_SERVICE = new FileUploadService();
    private final static LikeService LIKE_SERVICE = new LikeService();
    private final static CommentService COMMENT_SERVICE = new CommentService();
    private final static ContentService CONTENT_SERVICE = new ContentService();
    private final static UserService USER_SERVICE = new UserService();
    private final static FollowService FOLLOW_SERVICE = new FollowService();
    private final static ProfileService PROFILE_SERVICE = new ProfileService();
    private final static FeedService FEED_SERVICE = new FeedService();

    //DAO getters
    public static CommentDao getCommentDao() { return COMMENT_DAO; }
    public static CommentLikeDao getCommentLikeDao() { return COMMENT_LIKE_DAO; }
    public static CommentMediaDao getCommentMediaDao() { return COMMENT_MEDIA_DAO; }
    public static ContentDao getContentDao() { return CONTENT_DAO; }
    public static ContentLikeDao getContentLikeDao() { return CONTENT_LIKE_DAO; }
    public static ContentMediaDao getContentMediaDao() { return CONTENT_MEDIA_DAO; }
    public static FollowDao getFollowDao() { return FOLLOW_DAO; }
    public static UserDao getUserDao() { return USER_DAO; }

    //Service getters
    public static LikeCacheService getLikeCacheService() { return LIKE_CACHE_SERVICE; }
    public static FileUploadService getFileUploadService() { return FILE_UPLOAD_SERVICE; }
    public static LikeService getLikeService() { return LIKE_SERVICE; }
    public static CommentService getCommentService() { return COMMENT_SERVICE; }
    public static ContentService getContentService() { return CONTENT_SERVICE; }
    public static UserService getUserService() { return USER_SERVICE; }
    public static FollowService getFollowService() { return FOLLOW_SERVICE; }
    public static ProfileService getProfileService() { return PROFILE_SERVICE; }
    public static FeedService getFeedService() { return FEED_SERVICE; }
}