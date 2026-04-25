package com.itheima.controller;

import com.itheima.pojo.LogInResult;
import com.itheima.service.TokenService;
import com.itheima.service.UserService;
import com.itheima.util.ErrorCodeUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/user/login")
public class LoginServlet extends HttpServlet {
    private UserService userService = new UserService();
    private com.itheima.controller.BaseServlet baseServlet=new com.itheima.controller.BaseServlet();
    private TokenService tokenService=new TokenService();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try{
            String idStr=req.getParameter("id");
            String phone=req.getParameter("phone");
            String action=req.getParameter("action");
            System.out.println("LoginServlet receive login request: id=" + idStr + ", phone=" + phone + ", action=" + action);
            if(action==null||action.trim().isEmpty()){
                baseServlet.writeError(resp, ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
                return;
            }
            switch (action){
                case "register":
                    register(req,resp);
                    break;
                case "login":
                    login(req,resp);
                    break;
                default:
                    baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"未识别功能");
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            baseServlet.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,e.getMessage());
        }catch (Exception ex){
            ex.printStackTrace();
            baseServlet.writeError(resp,ErrorCodeUtil.SYSTEM_ERROR,ex.getMessage());
        }
    }



    protected void login(HttpServletRequest req, HttpServletResponse resp) throws Exception {
            String idStr=req.getParameter("id");
            String phone = req.getParameter("phone");
            String password = req.getParameter("password");
            if(idStr==null&&phone==null||password==null){
                baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
                return;
            }
        try{
            String username;
            String tokenStr;
            LogInResult ls;
            if(idStr==null||idStr.isEmpty()){//优先使用id登录，没有id就用手机号登录,这里用手机号
                ls=userService.login(phone,password);
            }
            else {
                long id=baseServlet.getLong(req,"id");
                ls=userService.login(id,password);
            }
            tokenStr = ls.getToken();
            username=ls.getUser().getUserName();
            baseServlet.writeSuccess(resp,tokenStr,username);
        }catch (RuntimeException e){
            e.printStackTrace();
            baseServlet.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,e.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace();
            baseServlet.writeError(resp,ErrorCodeUtil.SYSTEM_ERROR,ex.getMessage());
        }

    }

    protected void register(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String username=req.getParameter("username");
        String phone=req.getParameter("phone");
        String password=req.getParameter("password");//两次密码不一样会在前端拦截

        if(phone==null||password==null||username==null){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
            return;
        }
        if(phone.trim().isEmpty()||password.trim().isEmpty()||username.trim().isEmpty()){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
            return;
        }
        long id= userService.registerAsUser(username,password,phone);
        String tokenStr=tokenService.getNewToken(id);
        baseServlet.writeSuccess(resp,tokenStr,username);
    }
}
