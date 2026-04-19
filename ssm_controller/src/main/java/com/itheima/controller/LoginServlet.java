package com.itheima.controller;

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
    private BaseServlet baseServlet=new BaseServlet();
    private TokenService tokenService=new TokenService();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try{
            String idStr=req.getParameter("id");
            String phone=req.getParameter("phone");
            String action=req.getParameter("action");
            System.out.println("收到登录请求: id=" + idStr + ", phone=" + phone + ", action=" + action);
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
                baseServlet.writeError(resp,1,"输入不能为空");
                return;
            }
            String username;
            String tokenStr;
            if(idStr==null||idStr.isEmpty()){//优先使用id登录，没有id就用手机号登录
                tokenStr = userService.logInAsUserByPhone(phone, password);
                username=userService.findUsernameByPhone(phone);
                baseServlet.writeSuccess(resp,tokenStr,username);
            }
            else {
                long id=baseServlet.getLong(req,"id");
                tokenStr=userService.logInAsUserByID(id,password);
                username=userService.findUsernameById(id);
                baseServlet.writeSuccess(resp,tokenStr,username);
            }
    }

    protected void register(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String username=req.getParameter("username");
        String phone=req.getParameter("phone");
        String password=req.getParameter("password");//两次密码不一样会在前端拦截

        if(phone==null||password==null||username==null){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
        }
        if(phone.trim().isEmpty()||password.trim().isEmpty()||username.trim().isEmpty()){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
        }
        long id= userService.registerAsUser(username,password,phone);
        String tokenStr=tokenService.getNewToken(id);
        baseServlet.writeSuccess(resp,tokenStr,username);
    }
}
