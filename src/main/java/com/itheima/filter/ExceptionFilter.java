package com.itheima.filter;

import com.itheima.controller.BaseServletUtil;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.util.LogUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局异常处理：置于 Filter 链首位，统一把业务异常/未知异常转换为 JSON 响应。
 */
public class ExceptionFilter implements Filter {

    private static final Logger LOGGER = LogUtil.getLogger(ExceptionFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (BusinessException e) {
            LOGGER.log(Level.WARNING, "业务异常: code=" + e.getCode() + ", msg=" + e.getMessage());
            writeIfUncommitted((HttpServletResponse) response, e.getCode(), e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "未处理异常: " + request.getServletContext().getContextPath(), e);
            writeIfUncommitted((HttpServletResponse) response, ErrorCode.SERVER_ERROR);
        }
    }

    private void writeIfUncommitted(HttpServletResponse resp, int code, String msg) {
        if (resp.isCommitted()) {
            return;
        }
        try {
            BaseServletUtil.writeError(resp, code, msg);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "写入错误响应失败", e);
        }
    }

    private void writeIfUncommitted(HttpServletResponse resp, ErrorCode code) {
        writeIfUncommitted(resp, code.getCode(), code.getMessage());
    }
}
