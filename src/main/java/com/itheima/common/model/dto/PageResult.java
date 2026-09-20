package com.itheima.common.model.dto;

import java.util.List;

/**
 * 分页信封（跨域共享，T14 从 {@code content.model.dto} 上移公共包）。
 *
 * <p>全项目**唯一**的分页信封：{@code list / total / page / pageSize / totalPages}。
 * T14 前 follow 域另有一份同形的 {@code follow.model.dto.FollowPageResult}（当时为避开
 * follow→content 包层环而自建），本类上移后该类已删除，两域统一复用本类——
 * JSON 字段名与推导公式逐字段不变，前端按字段名消费、零改动。
 *
 * <p>{@code totalPages} 由 {@code total} 与 {@code pageSize} 推导（{@code pageSize <= 0}
 * 时为 0）；缺省（不传分页参数）路径是否构造信封由各域 Controller 决定。
 */
public class PageResult<T> {
    private List<T> list;
    private int total;
    private int page;
    private int pageSize;
    private int totalPages;

    public PageResult() {
    }

    public PageResult(List<T> list, int total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }
}
