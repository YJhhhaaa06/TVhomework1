package com.itheima.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public class ContentVO extends ContentCacheDTO {
    private boolean isLiked;
    private boolean isFollowed;

    public ContentVO() {
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }

    public boolean getIsFollowed() {
        return isFollowed;
    }

    public void setIsFollowed(boolean isFollowed) {
        this.isFollowed = isFollowed;
    }

    @Override
    @JsonIgnore
    public String getVideoUrl() {
        return super.getVideoUrl();
    }

    @Override
    @JsonIgnore
    public List<String> getImageUrls() {
        return super.getImageUrls();
    }
}
