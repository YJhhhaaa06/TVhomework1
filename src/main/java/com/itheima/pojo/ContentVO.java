package com.itheima.pojo;

//首页，非详情页的VO
public class ContentVO extends ContentCacheVO {
    private boolean isLiked;
    private boolean isFollowed;
    private String coverUrl;

    public ContentVO() {
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }

    public boolean getIsFollow() {
        return isFollowed;
    }

    public void setIsFollow(boolean isFollow) {
        this.isFollowed = isFollow;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }


}