package com.itheima.pojo;

public class ContentBaseVO {
    private long id;//视频、动态id
    private long authorId;//作者Id
    private int type;//内容类型1视频 2图文
    private String title;//标题
    private String description;//文本
    private int categoryId;//分区
    private int commentCount;//评论数
    private int likeCount;//点赞数
    private String authorName;//作者名
    private boolean isFollow;//当前用户是否关注了该作者

    public ContentBaseVO() {
    }

    public ContentBaseVO(long id, long authorId, int type, String title, String description, int categoryId, int commentCount, int likeCount, String authorName) {
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

    public boolean getIsFollow() {
        return isFollow;
    }

    public void setIsFollow(boolean follow) {
        isFollow = follow;
    }
}
