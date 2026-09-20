package com.itheima.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itheima.exception.ErrorCode;
import com.itheima.util.ResultUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class BaseServletUtil {

    // HTTP 请求/响应侧唯一 ObjectMapper（T16 收敛：BaseServlet / RequestParser 均复用此处）
    public static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // 统一返回成功
    public static void writeSuccess(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data));
    }

    // 统一返回错误
    public static void writeError(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.error(code, msg));
    }

    // 枚举重载：使用自定义消息
    public static void writeError(HttpServletResponse resp, ErrorCode code, String msg) throws IOException {
        writeError(resp, code.getCode(), msg);
    }

    // 枚举重载：使用默认中文消息
    public static void writeError(HttpServletResponse resp, ErrorCode code) throws IOException {
        writeError(resp, code.getCode(), code.getMessage());
    }

    // 分页参数解析（T5 收敛公共；默认 page=1 / pageSize=10；T10-B 版本上限参数化，原语义不变）

    /** 公共上限 50（原 Feed/Profile 语义，其它分页接口沿用）。 */
    public static final int DEFAULT_PAGE_SIZE_MAX = 50;

    /** 公共缺省信封大小 10（原语义；域级信封见三参重载，T11-A）。 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    public static int parsePage(HttpServletRequest req) {
        String param = req.getParameter("page");
        if (param == null || param.isBlank()) {
            return 1;
        }
        try {
            int p = Integer.parseInt(param);
            return p > 0 ? p : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 默认解析：上限 50、缺省 10（T10-B 起委托带 max 重载，公共语义零变化）。 */
    public static int parsePageSize(HttpServletRequest req) {
        return parsePageSize(req, DEFAULT_PAGE_SIZE_MAX);
    }

    /**
     * 域级上限版（T10-B）：上限 {@code max}、缺省仍为公共默认 10（如评论域 500）；语义同原公共解析。
     */
    public static int parsePageSize(HttpServletRequest req, int max) {
        return parsePageSize(req, max, DEFAULT_PAGE_SIZE);
    }

    /**
     * 域级「上限 + 信封大小」版（T11-A）：信封大小改由**后端域级常量**决定，前端只传 {@code page} 即可。
     *
     * <p>传了 {@code pageSize} → {@code min(s, max)}（**上限与 pytest 小信封跨页验证能力同时保留**）；
     * 未传 / 非法 / ≤0 → {@code min(defaultSize, max)}（域级信封）。**返回值恒 ≤ max**——把"信封不会
     * 超过上限"这一不变量收进方法内，防某域把 {@code defaultSize} 配得比 {@code max} 大而悄悄越界。
     * 公共语义零变化——两参重载委托本方法并传 {@link #DEFAULT_PAGE_SIZE}（10），与改造前逐字节一致。
     */
    public static int parsePageSize(HttpServletRequest req, int max, int defaultSize) {
        int fallback = Math.min(defaultSize, max);
        String param = req.getParameter("pageSize");
        if (param == null || param.isBlank()) {
            return fallback;
        }
        try {
            int s = Integer.parseInt(param);
            return s > 0 ? Math.min(s, max) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
