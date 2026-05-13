package com.itheima.controller;

import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.pojo.ProfileVO;
import com.itheima.service.ProfileService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/profile")
public class ProfileController extends BaseServlet {
    @Inject
    private ProfileService profileService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            long profileUserId = parseUserId(req);
            int page = parsePage(req);
            int pageSize = parsePageSize(req);
            Long currentUserId = (Long) req.getAttribute("userId");

            ProfileVO profile = profileService.getProfile(profileUserId, currentUserId, page, pageSize);
            BaseServletUtil.writeSuccess(resp, profile);
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    private long parseUserId(HttpServletRequest req) {
        String param = req.getParameter("userId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 userId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("userId 格式错误");
        }
    }

    private int parsePage(HttpServletRequest req) {
        String param = req.getParameter("page");
        if (param == null || param.isBlank()) {
            return 1;
        }
        try {
            int p = Integer.parseInt(param);
            return p > 0 ? p : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private int parsePageSize(HttpServletRequest req) {
        String param = req.getParameter("pageSize");
        if (param == null || param.isBlank()) {
            return 10;
        }
        try {
            int s = Integer.parseInt(param);
            return s > 0 ? Math.min(s, 50) : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}