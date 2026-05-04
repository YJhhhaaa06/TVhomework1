package com.itheima.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@WebServlet("/upload/*")
public class UploadServlet extends HttpServlet {

    // 这里指向你 D 盘 upload 根目录
    private static final String BASE_PATH = "D:/stone";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // 获取 URL 中 /upload 后面的路径
        String pathInfo = req.getPathInfo(); // 例如 /video/xxx.mp4

        if (pathInfo == null || pathInfo.equals("/")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "文件路径不能为空");
            return;
        }

        // 拼接磁盘文件路径
        File file = new File(BASE_PATH, pathInfo);

        if (!file.exists() || !file.isFile()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
            return;
        }

        // 根据文件类型设置 MIME
        String mime = getServletContext().getMimeType(file.getName());
        if (mime == null) {
            mime = "application/octet-stream";
        }
        resp.setContentType(mime);
        resp.setContentLengthLong(file.length());

        // 输出文件到浏览器
        Files.copy(file.toPath(), resp.getOutputStream());
    }

    // 可以选择支持 HEAD 请求（浏览器预览视频时会用到）
    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        doGet(req, resp);
    }
}
