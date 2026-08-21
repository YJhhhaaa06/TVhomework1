package com.itheima.model.cache;

import java.util.List;

public class CommentCacheDTO {
    private String username;
    private long commentId;
    private long contentId;
    private long userId;
    private String content;
    private Long parentId;
    private int likeCount;
    private List<CommentCacheDTO> children;

    public CommentCacheDTO() {
    }

    public CommentCacheDTO(String username, long commentId, long contentId, long userId, String content, Long parentId, int likeCount) {
        this.username = username;
        this.commentId = commentId;
        this.contentId = contentId;
        this.userId = userId;
        this.content = content;
        this.parentId = parentId;
        this.likeCount = likeCount;

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

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
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

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public List<CommentCacheDTO> getChildren() {
        return children;
    }

    public void setChildren(List<CommentCacheDTO> children) {
        this.children = children;
    }
}