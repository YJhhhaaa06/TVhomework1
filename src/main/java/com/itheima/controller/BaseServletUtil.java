package com.itheima.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.util.ResultUtil;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class BaseServletUtil extends HttpServlet {

    protected static final ObjectMapper mapper = new ObjectMapper();

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




}
