package com.itheima.DTO;

public class SearchDTO {
    private String keyword;
    private Long contentId;

    public SearchDTO() {
    }

    public SearchDTO(String keyword) {
        this.keyword = keyword;
    }

    public SearchDTO(Long contentId) {
        this.contentId = contentId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }
}
