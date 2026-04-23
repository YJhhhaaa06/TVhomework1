package com.itheima.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.util.ResultUtil;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class BaseServlet extends HttpServlet {

    protected static final ObjectMapper mapper = new ObjectMapper();

    // 统一返回成功
    protected  void writeSuccess(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data));
    }
    protected  void writeSuccess(HttpServletResponse resp, Object data,String username) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data,username));
    }

    // 统一返回错误
    protected void writeError(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.error(code, msg));
    }

    // 参数获取（自动校验）
    protected Long getLong(HttpServletRequest req, String name) {
        String val = req.getParameter(name);

        if (val == null || val.trim().isEmpty()) {
            throw new RuntimeException(name + "_EMPTY");
        }

        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException(name + "_INVALID");
        }
    }
}
