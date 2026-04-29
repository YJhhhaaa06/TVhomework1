package com.itheima.pojo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class Content {
    private long id;//视频、动态id
    private long authorId;//作者Id
    private int type;//内容类型
    private String title;//标题
    private String description;//文本
    private int categoryId;//分区
    private int commentCount;//评论数
    private int likeCount;//点赞数
    private String authorName;//作者名
//    private LocalDateTime crateTime=LocalDateTime.now();//时间
    public List<Comment> commentList;//评论

    private String videoUrl;
    private String coverUrl;
    private List<String> imageUrls;



    public Content() {
    }

    //为上传内容准备的构造，检查将在这里完成
    public Content(long authorId, String description, int categoryId, String title, int type) {
        this.authorId = authorId;
        this.description = description;
        this.categoryId = categoryId;
        this.title = title;
        this.type = type;
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

//    public LocalDateTime getCrateTime() {
//        return crateTime;
//    }
//
//    public void setCrateTime(LocalDateTime crateTime) {
//        this.crateTime = crateTime;
//    }

    public List<Comment> getCommentList() {
        return commentList;
    }

    public void setCommentList(List<Comment> commentList) {
        this.commentList = commentList;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }
}
