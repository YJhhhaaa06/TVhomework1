package com.itheima.command;

public class CommentCommand {

    private long contentId;
    private long userId;
    private String message;
    private Long parentId;

    public CommentCommand(long contentId, long userId, String message, Long parentId) {
        this.contentId = contentId;
        this.userId = userId;
        this.message = message;
        this.parentId = parentId;
    }



    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }
}
