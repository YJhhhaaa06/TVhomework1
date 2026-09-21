package com.itheima.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itheima.exception.ErrorCode;
import com.itheima.util.LogContext;
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
        // 结果码收口（T3 log-03，D5）：写响应时把 body code 写入 LogContext，
        // 供最外层 AccessLogFilter 取用（ResultUtil.success 恒为 code=200）
        LogContext.setResultCode(200);
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data));
    }

    // 统一返回错误
    public static void writeError(HttpServletResponse resp, int code, String msg) throws IOException {
        // 结果码收口（T3 log-03，D5）：业务/过滤/异常统一出口，body code 与访问日志一致
        LogContext.setResultCode(code);
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

    // 分页参数解析（T5 收敛公共；T10-B 上限参数化；T11-A 域级信封；T19 归一化下沉为 request 无关方法 + 删死重载）

    /**
     * 页码归一（T19：从 {@link #parsePage} 下沉为**不依赖 request** 的形态，供 JSON body 形态的接口复用）：
     * 缺省 / ≤0 → 1。语义与 T5 起的 {@code parsePage} 逐字节一致。
     */
    public static int normalizePage(Integer raw) {
        return (raw == null || raw <= 0) ? 1 : raw;
    }

    /**
     * 信封大小归一（T19：从三参 {@link #parsePageSize} 下沉，同样不依赖 request）：缺省（{@code null}/≤0）
     * → {@code min(defaultSize, max)}；传了 → {@code min(raw, max)}。**返回值恒 ≤ max**——把"信封不会
     * 超过上限"这一不变量收进方法内，防某域把 {@code defaultSize} 配得比 {@code max} 大而悄悄越界。
     */
    public static int normalizePageSize(Integer raw, int max, int defaultSize) {
        int fallback = Math.min(defaultSize, max);
        return (raw == null || raw <= 0) ? fallback : Math.min(raw, max);
    }

    public static int parsePage(HttpServletRequest req) {
        String param = req.getParameter("page");
        if (param == null || param.isBlank()) {
            return 1;
        }
        try {
            return normalizePage(Integer.parseInt(param));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 域级「上限 + 信封大小」版（T11-A；**T19 起为唯一的 request 形态重载**）：信封大小由**后端域级常量**
     * 决定，前端只传 {@code page} 即可。
     *
     * <p>传了 {@code pageSize} → {@code min(s, max)}（**上限与 pytest 小信封跨页验证能力同时保留**——
     * T19 拍板"不采纳后端硬忽略 pageSize"：否则 feed/search/profile 的"页间不重不漏"将失去唯一
     * 低成本验证手段）；未传 / 非法 / ≤0 → {@code min(defaultSize, max)}（域级信封）。
     *
     * <p><b>T19 删除</b>：无参重载（公共 50/10）与两参重载（域级上限 + 公共缺省 10）——三域改用本重载后
     * 二者成为零调用死代码（两参重载自 T11-B 起即已零调用），`DEFAULT_PAGE_SIZE_MAX` /
     * `DEFAULT_PAGE_SIZE` 随之删除。新域一律显式声明自己的 `XXX_PAGE_SIZE_MAX/DEFAULT`。
     */
    public static int parsePageSize(HttpServletRequest req, int max, int defaultSize) {
        String param = req.getParameter("pageSize");
        if (param == null || param.isBlank()) {
            return normalizePageSize(null, max, defaultSize);
        }
        try {
            return normalizePageSize(Integer.parseInt(param), max, defaultSize);
        } catch (NumberFormatException e) {
            return normalizePageSize(null, max, defaultSize);
        }
    }
}
