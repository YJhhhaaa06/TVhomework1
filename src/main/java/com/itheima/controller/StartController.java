package com.itheima.controller;

import com.itheima.pojo.RecommendVO;
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
    private BaseServletUtil baseServletUtil =new BaseServletUtil();


        private ContentService contentService = ContentService.getInstance();
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            // 1. 设置响应格式
            resp.setContentType("application/json;charset=utf-8");

            // 2. 获取推荐视频（例如取 12 条）
            List<RecommendVO> recommend = contentService.getRecommend(12);

            // 输出
            baseServletUtil.writeSuccess(resp,recommend);

        }
    }

