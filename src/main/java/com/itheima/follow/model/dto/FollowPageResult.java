package com.itheima.follow.model.dto;

import java.util.List;

/**
 * 关注/粉丝列表分页信封（T7 B2 新增）。
 *
 * <p>字段与 JSON 形状与 content 域 {@code content.model.dto.PageResult} **逐字段一致**
 * （{@code list / total / page / pageSize / totalPages}），前端按字段名消费，两种域名下的信封
 * 对调用方透明。
 *
 * <p><b>为什么不直接复用 {@code PageResult}</b>：该 DTO 归属 content 域，而 content 域已
 * import {@code follow.service.FollowCache}（Feed/Profile/ContentStatusFiller），follow 域反向
 * import {@code content.model.dto} 会形成**新的 follow↔content 包层环**（与 U-07 的
 * content↔comment 同型，属本项目明确追踪的包架构债）。故本任务在 follow 域内放置同形信封，
 * "PageResult 上移公共包供多域复用"记入 `UNPLANNED_ISSUES.md` 留池（跨域重构不属本任务范围）。
 *
 * <p>totalPages 由 {@code total} 与 {@code pageSize} 推导（与 PageResult 同公式）；
 * 缺省（不传分页参数）路径不构造本信封——那条路径维持返回全量数组（对外行为零变化）。
 */
public class FollowPageResult<T> {

    private List<T> list;
    private int total;
    private int page;
    private int pageSize;
    private int totalPages;

    public FollowPageResult() {
    }

    public FollowPageResult(List<T> list, int total, int page, int pageSize) {
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
