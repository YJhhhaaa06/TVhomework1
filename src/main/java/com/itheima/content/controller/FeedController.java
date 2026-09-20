package com.itheima.content.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.common.model.dto.PageResult;
import com.itheima.ioc.annotation.Inject;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.service.FeedService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/feed")
public class FeedController extends BaseServlet {
    @Inject
    private FeedService feedService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Long currentUserId = (Long) req.getAttribute("userId");
        int page = BaseServletUtil.parsePage(req);
        int pageSize = BaseServletUtil.parsePageSize(req);

        PageResult<ContentVO> result = feedService.getFeed(currentUserId, page, pageSize);
        BaseServletUtil.writeSuccess(resp, result);
    }
}
