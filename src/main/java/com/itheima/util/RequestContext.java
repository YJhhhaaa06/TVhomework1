package com.itheima.util;

/**
 * 保存当前请求的 context path，供服务层拼接媒体 URL 使用。
 * 非请求线程（启动刷新、定时任务）回退到应用启动时记录的默认 context path。
 *
 * <p>分工约定（NEEDS 4.0 D6）：**业务类字段放本类**（context path），**日志类字段放 {@link LogContext}**
 * （reqId）。两者互不干扰：本类由内层 EncodingFilter 在 try/finally 中 set/clear，{@link LogContext}
 * 只在最外层 filter（T3）一处 set/clear——不共用清理点，否则 reqId 会被本类的 clear 提前清掉、
 * 使异常日志丢掉请求标识。本类语义与行为自 D6 起未变（含非请求线程的默认值兜底）。
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
