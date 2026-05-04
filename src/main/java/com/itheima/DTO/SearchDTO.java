package com.itheima.DTO;

public class SearchDTO {
    private String keyword;
    private long contentId;

    public SearchDTO() {
    }

    public SearchDTO(String keyword) {
        this.keyword = keyword;
    }

    public SearchDTO(long contentId) {
        this.contentId = contentId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }
}
