package com.itheima.controller;

import com.itheima.ioc.IocContainer;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;

public class BaseServlet extends HttpServlet {

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        IocContainer.getInstance().injectInto(this);
    }
}