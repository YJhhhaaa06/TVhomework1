package com.itheima.pojo;

import java.time.LocalDateTime;
import java.util.List;

public class Comment {
    //为了可扩展，我把数据库里面所有字段都加进来

    private String username;//这个不属comment表
    private long commentId;
    private long videoId;
    private long userId;
    private String content;
    private Long parentId;//Long类似Integer,可以为NULL
    //父评论ID
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int likeCount;
    private boolean isDeleted;//false是未删除
    private List<Comment> children;

    public List<Comment> getChildren() {
        return children;
    }

    public void setChildren(List<Comment> children) {
        this.children = children;
    }

    public Comment(String username, long commentId, long videoId, long userId, String content, Long parentId, LocalDateTime createTime, LocalDateTime updateTime, int likeCount, boolean isDeleted) {
        this.username = username;
        this.commentId = commentId;
        this.videoId = videoId;
        this.userId = userId;
        this.content = content;
        this.parentId = parentId;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.likeCount = likeCount;
        this.isDeleted = isDeleted;
    }

    public Comment() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public long getCommentId() {
        return commentId;
    }

    public void setCommentId(long commentId) {
        this.commentId = commentId;
    }

    public long getVideoId() {
        return videoId;
    }

    public void setVideoId(long videoId) {
        this.videoId = videoId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

}
