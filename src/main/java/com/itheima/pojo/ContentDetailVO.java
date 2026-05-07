package com.itheima.pojo;

import java.util.List;

public class ContentDetailVO extends ContentCacheVO {

    private String videoUrl;
    private String coverUrl;
    private List<String> imageUrls;

    public ContentDetailVO() {
    }

    public ContentDetailVO(long id, long authorId, int type, String title, String description, int categoryId, int commentCount, int likeCount, String authorName, String videoUrl, String coverUrl) {
        super(id, authorId, type, title, description, categoryId, commentCount, likeCount, authorName);
        this.videoUrl = videoUrl;
        this.coverUrl = coverUrl;
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