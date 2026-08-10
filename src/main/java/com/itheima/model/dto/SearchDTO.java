package com.itheima.model.dto;

public class SearchDTO {
    private String keyword;
    private Long contentId;
    private Integer page;
    private Integer pageSize;

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

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
