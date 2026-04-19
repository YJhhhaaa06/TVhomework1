package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.pojo.LogInResult;
import com.itheima.pojo.User;
import com.itheima.service.UserService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

//让url:http://localhost:8080/MyAPP/user/login映射到这个类
//
@WebServlet("/user/login")
public class UserLoginServlet extends HttpServlet {

    private UserService userService = new UserService();
    private BaseServlet baseServlet=new BaseServlet();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String idStr=req.getParameter("id");
            String phone = req.getParameter("phone");
            String password = req.getParameter("password");
            if(idStr==null&&phone==null||password==null){
                baseServlet.writeError(resp,1,"输入不能为空");
            }
            com.itheima.pojo.LogInResult res;
            if(idStr==null||idStr.isEmpty()){//优先使用id登录，没有id就用手机号登录
                res = userService.logInAsUserByPhone(phone, password);
            }
            else {
                long id=baseServlet.getLong(req,"id");
                res=userService.logInAsUserByID(id,password);
                baseServlet.writeSuccess(resp,res);
            }
            //打印结果（调试用）
            System.out.println("登录成功：" + res);
        } catch (RuntimeException e) {
            e.printStackTrace(); // 打印错误到控制台
            baseServlet.writeError(resp,2,e.getMessage());
        }catch (Exception e) {
            e.printStackTrace();
            baseServlet.writeError(resp, 500, "SYSTEM_ERROR");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("Servlet 已运行");
    }
}
