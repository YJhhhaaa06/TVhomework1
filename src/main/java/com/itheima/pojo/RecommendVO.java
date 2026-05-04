package com.itheima.pojo;

public class RecommendVO extends ContentBaseVO{

    private String coverUrl;

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
}
