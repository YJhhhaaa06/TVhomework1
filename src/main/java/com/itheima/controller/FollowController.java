package com.itheima.controller;

import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.service.FollowService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/follow/*")
public class FollowController extends HttpServlet {
    private FollowService followService = new FollowService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            String action = req.getPathInfo();
            if(action==null){
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR,"action不能为空");
                return;
            }
            Long userId = (Long) req.getAttribute("userId");
            long followedUserId = parseFollowedUserId(req);

            switch (action) {
                case "/add":
                    followService.follow(userId, followedUserId);
                    BaseServletUtil.writeSuccess(resp, "关注成功");
                    break;
                case "/remove":
                    followService.unfollow(userId, followedUserId);
                    BaseServletUtil.writeSuccess(resp, "已取关");
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
            }
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    private long parseFollowedUserId(HttpServletRequest req) {
        String param = req.getParameter("followedUserId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 followedUserId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("followedUserId 格式错误");
        }
    }
}
