package com.itheima.controller;

import com.itheima.pojo.Video;
import com.itheima.service.VideoService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/start")
public class StartServlet extends HttpServlet {
    private  BaseServlet baseServlet=new BaseServlet();


        private VideoService videoService = VideoService.getInstance();

        @Override
        public void init() throws ServletException {
            // 确保服务初始化，启动定时刷新任务
            videoService.init();
        }
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            // 1. 设置响应格式
            resp.setContentType("application/json;charset=utf-8");

            // 2. 获取推荐视频（例如取 10 条）
            List<Video> recommendedVideos = videoService.getRecommendedVideos(12);

            // 输出
            baseServlet.writeSuccess(resp,recommendedVideos);

        }
    }

