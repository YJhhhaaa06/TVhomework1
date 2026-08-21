package com.itheima.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itheima.exception.ErrorCode;
import com.itheima.util.ResultUtil;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class BaseServletUtil extends HttpServlet {

    protected static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // 统一返回成功
    public  static void writeSuccess(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data));
    }
    protected static void writeSuccess(HttpServletResponse resp, Object data,String username) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(resp.getWriter(), ResultUtil.success(data,username));
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




}
