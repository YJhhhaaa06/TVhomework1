package com.itheima.controller;

import com.itheima.service.UserService;
import com.itheima.util.ErrorCodeUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/menu")
public class MenuServlet extends HttpServlet {
    private UserService userService = new UserService();
    private BaseServlet baseServlet=new BaseServlet();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try{
            String action=req.getParameter("action");
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
            com.itheima.pojo.LogInResult res;
            if(idStr==null||idStr.isEmpty()){//优先使用id登录，没有id就用手机号登录
                res = userService.logInAsUserByPhone(phone, password);
                baseServlet.writeSuccess(resp,res);
            }
            else {
                long id=baseServlet.getLong(req,"id");
                res=userService.logInAsUserByID(id,password);
                baseServlet.writeSuccess(resp,res);
            }
    }

    protected void register(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String username=req.getParameter("username");
        String phone=req.getParameter("phone");
        String password=req.getParameter("password");
        String confirmPassword=req.getParameter("password");

        if(phone==null||password==null||confirmPassword==null||username==null){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
        }
        if(phone.trim().isEmpty()||password.trim().isEmpty()||confirmPassword.trim().isEmpty()||username.trim().isEmpty()){
            baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
        }
        if(!password.equals(confirmPassword)){
            baseServlet.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,"两次输入密码不同");
        }
        userService.registerAsUser(username,password,phone);
        baseServlet.writeSuccess(resp,"注册成功");
    }
}
