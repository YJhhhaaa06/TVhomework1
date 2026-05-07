package com.itheima.pojo;

public class ContentCacheVO {
    private long id;
    private long authorId;
    private int type;
    private String title;
    private String description;
    private int categoryId;
    private int commentCount;
    private int likeCount;
    private String authorName;

    public ContentCacheVO() {
    }

    public ContentCacheVO(long id, long authorId, int type, String title, String description, int categoryId, int commentCount, int likeCount, String authorName) {
        this.id = id;
        this.authorId = authorId;
        this.type = type;
        this.title = title;
        this.description = description;
        this.categoryId = categoryId;
        this.commentCount = commentCount;
        this.likeCount = likeCount;
        this.authorName = authorName;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(long authorId) {
        this.authorId = authorId;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(int categoryId) {
        this.categoryId = categoryId;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }
}