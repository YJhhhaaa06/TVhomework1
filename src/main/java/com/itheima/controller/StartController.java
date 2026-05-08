package com.itheima.controller;

import com.itheima.factory.BeanFactory;
import com.itheima.pojo.ContentVO;
import com.itheima.service.ContentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/start")
public class StartController extends HttpServlet {
    private ContentService contentService = BeanFactory.getContentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json;charset=utf-8");

        List<ContentVO> recommend = contentService.getRecommend(12);

        Long userId = (Long) req.getAttribute("userId");
        contentService.fillLikeAndFollowBatch(recommend, userId);

        BaseServletUtil.writeSuccess(resp, recommend);
    }
}