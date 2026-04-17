package com.itheima.controller;

import com.itheima.pojo.Comment;
import com.itheima.pojo.Token;
import com.itheima.service.CommentService;
import com.itheima.util.TokenUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.jar.JarException;

@WebServlet("/comment/add")
public class CommentAddServlet extends HttpServlet {
    private CommentService commentService=new CommentService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=UTF-8");
        try{
            Long videoId=Long.parseLong(req.getParameter("videoId"));
            Long userId=Long.parseLong(req.getParameter("userId"));
            String content = req.getParameter("content");
            String parentIdStr = req.getParameter("parentId");
            Long parentId = null;
            if (parentIdStr != null && !parentIdStr.isEmpty()) {
                parentId = Long.parseLong(parentIdStr);
            }
            commentService.addComment(userId,videoId,parentId,content);
            resp.getWriter().write("评论成功");
        }catch (Exception e){
            e.printStackTrace();
            resp.getWriter().write("评论失败"+e.getMessage());
            throw e;
        }
    }

}
