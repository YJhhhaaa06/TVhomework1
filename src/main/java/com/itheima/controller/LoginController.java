package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.model.command.ChangePasswordCommand;
import com.itheima.model.command.CommandConverter;
import com.itheima.model.command.LoginCommand;
import com.itheima.model.command.RegisterCommand;
import com.itheima.model.dto.ChangePasswordDTO;
import com.itheima.model.dto.LoginDTO;
import com.itheima.model.dto.RegisterDTO;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.LoginVO;
import com.itheima.service.UserService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/user/*")
public class LoginController extends BaseServlet {
    private RequestParser requestParser=new RequestParser();
    @Inject
    private UserService userService;
    private ObjectMapper mapper=new ObjectMapper();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action=req.getPathInfo();//path去掉了/user的结果
        if(action==null){
            BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,"请求异常，请重试");
            return;
        }
        try{
            switch (action){
                case ("/login"):
                    login(req,resp);
                    break;
                case ("/register"):
                    register(req, resp);
                    break;
                case ("/changePassword"):
                    changePassword(req, resp);
                    break;
                default:
                    BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,"请求异常，请重试");
            }
        }catch (BusinessException e){
            e.printStackTrace();
            System.out.println("Business"+e.getMessage());
            BaseServletUtil.writeError(resp,e.getCode(),e.getMessage());
        } catch (Exception e){
            e.printStackTrace();
            System.out.println(e.getMessage());
            BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,e.getMessage());
        }
    }


    protected void login(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        LoginDTO dto = RequestParser.parse(req,LoginDTO.class);
        System.out.println("finish parse");
        LoginCommand loginCommand= CommandConverter.loginToCommand(dto);
        LoginVO ls=userService.login(loginCommand);
        BaseServletUtil.writeSuccess(resp,ls);
    }

    protected void register(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        RegisterDTO dto = RequestParser.parse(req, RegisterDTO.class);
        RegisterCommand rc = CommandConverter.registerToCommand(dto);
        long id = userService.registerAsUser(rc);
        LoginVO ls = userService.login(id, rc.getPassword());
        BaseServletUtil.writeSuccess(resp, ls);
    }

    protected void changePassword(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Long userId = (Long) req.getAttribute("userId");
        if (userId == null) {
            BaseServletUtil.writeError(resp, ErrorCode.UNAUTHORIZED, "请先登录");
            return;
        }
        ChangePasswordDTO dto = RequestParser.parse(req, ChangePasswordDTO.class);
        ChangePasswordCommand command = CommandConverter.changePasswordToCommand(dto);
        userService.changePassword(userId, command);
        BaseServletUtil.writeSuccess(resp, null);
    }
}
