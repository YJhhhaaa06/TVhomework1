package com.itheima.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.itheima.util.RequestContext;

import java.io.IOException;

// /* 表示拦截所有请求，包括 Servlet 和 HTML/JSP
@WebFilter("/*")
public class EncodingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        // 核心：设置请求和响应编码
        req.setCharacterEncoding("UTF-8");
        try {
            // 记录本次请求的 context path，服务层拼接 /upload 等媒体 URL 时使用
            RequestContext.setContextPath(((HttpServletRequest) req).getContextPath());
            // 放行
            chain.doFilter(req, resp);
        } finally {
            RequestContext.clear();
        }
    }
}

