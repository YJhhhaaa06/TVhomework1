package com.itheima.controller;

import com.itheima.DTO.PageResult;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.factory.BeanFactory;
import com.itheima.pojo.ContentVO;
import com.itheima.service.FeedService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/feed")
public class FeedController extends HttpServlet {
    private FeedService feedService = BeanFactory.getFeedService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            Long currentUserId = (Long) req.getAttribute("userId");
            int page = parsePage(req);
            int pageSize = parsePageSize(req);

            PageResult<ContentVO> result = feedService.getFeed(currentUserId, page, pageSize);
            BaseServletUtil.writeSuccess(resp, result);
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
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