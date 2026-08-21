package com.itheima.util;

/**
 * 保存当前请求的 context path，供服务层拼接媒体 URL 使用。
 * 非请求线程（启动刷新、定时任务）回退到应用启动时记录的默认 context path。
 */
public final class RequestContext {

    private static volatile String defaultContextPath = "";
    private static final ThreadLocal<String> CONTEXT_PATH = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setDefaultContextPath(String contextPath) {
        defaultContextPath = contextPath == null ? "" : contextPath;
    }

    public static void setContextPath(String contextPath) {
        CONTEXT_PATH.set(contextPath == null ? "" : contextPath);
    }

    public static void clear() {
        CONTEXT_PATH.remove();
    }

    public static String getContextPath() {
        String value = CONTEXT_PATH.get();
        return value != null ? value : defaultContextPath;
    }
}
