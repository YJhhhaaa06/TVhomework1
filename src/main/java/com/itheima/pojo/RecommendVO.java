package com.itheima.pojo;

public class RecommendVO extends ContentCacheVO {

    private String coverUrl;
    private boolean isFollow;

    public RecommendVO() {
    }

    public RecommendVO(long id, long authorId, int type, String title, String description, int categoryId, int commentCount, int likeCount, String authorName) {
        super(id, authorId, type, title, description, categoryId, commentCount, likeCount, authorName);
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public boolean getIsFollow() {
        return isFollow;
    }

    public void setIsFollow(boolean isFollow) {
        this.isFollow = isFollow;
    }
}