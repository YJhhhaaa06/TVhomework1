package com.itheima.controller;

import java.io.IOException;
import jakarta.servlet.http.*;
import jakarta.servlet.*;
import java.io.IOException;

public class TestServlet extends HttpServlet{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("hello world");
    }
}
