package com.itheima.exception;

/**
 * 统一错误码（code + 中文默认消息）。
 */
public enum ErrorCode {
    // ===== 参数 (400) =====
    PARAM_ERROR(400, "参数错误"),
    INVALID_PHONE(400, "手机号格式错误"),
    INVALID_PASSWORD(400, "密码格式错误"),

    // ===== 认证 (401) =====
    UNAUTHORIZED(401, "请先登录"),
    USER_NOT_FOUND(401, "用户不存在"),
    WRONG_PASSWORD(401, "密码错误"),
    TOKEN_EXPIRED(401, "令牌已过期"),

    // ===== 权限 (403) =====
    FORBIDDEN(403, "无权访问"),
    ACCESS_DENIED(403, "无权访问"),

    // ===== 资源 (404) =====
    NOT_FOUND(404, "资源不存在"),
    CONTENT_NOT_FOUND(404, "内容不存在"),
    COMMENT_NOT_FOUND(404, "评论不存在"),

    // ===== 冲突 (409) =====
    CONFLICT(409, "操作冲突"),
    DUPLICATE_LIKE(409, "不可重复点赞"),
    DUPLICATE_PHONE(409, "手机号已被使用"),

    // ===== 服务器 (500) =====
    SERVER_ERROR(500, "服务器内部错误"),
    DATABASE_ERROR(500, "数据库操作失败"),
    CACHE_ERROR(500, "缓存操作失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
