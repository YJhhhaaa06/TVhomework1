package com.itheima.model.vo;

/**
 * 管理端内容清单视图（A2 审核下架用）。
 * hidden 表示该内容当前处于管理员下架状态（content.is_deleted == 2）。
 */
public class AdminContentVO {
    private long id;
    private String title;
    private int type;
    private String authorName;
    private boolean hidden;

    public AdminContentVO() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }
}
