package com.itheima.pojo;

public class ContentDetailVO extends ContentCacheDTO {
    private boolean isLiked;
    private boolean isFollowed;

    public ContentDetailVO() {
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
}