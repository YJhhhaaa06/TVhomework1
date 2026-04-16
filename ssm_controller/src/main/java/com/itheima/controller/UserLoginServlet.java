package com.itheima.controller;

import com.itheima.pojo.LogInResult;
import com.itheima.pojo.User;
import com.itheima.service.UserService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
//让url:http://localhost:8080/MyAPP/user/login映射到这个类
//
@WebServlet("/user/login")
public class UserLoginServlet extends HttpServlet {

    private UserService userService = new UserService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        resp.setContentType("text/plain;charset=UTF-8");

        String phone = req.getParameter("phone");
        String password = req.getParameter("password");

        //参数校验
        if (phone == null || password == null || phone.isEmpty() || password.isEmpty()) {
            resp.getWriter().write("参数不能为空");
            return;
        }

        try {
            com.itheima.pojo.LogInResult res =
                    userService.logInAsUserByPhone(phone, password);

            //打印结果（调试用）
            System.out.println("登录成功：" + res);

            //返回更详细信息
            resp.getWriter().write("登录成功：" + res.getUser().getUserName()+res.getToken());

        } catch (Exception e) {
            e.printStackTrace(); // 打印错误到控制台

            resp.getWriter().write("登录失败：" + e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("Servlet 已运行");
    }
}
