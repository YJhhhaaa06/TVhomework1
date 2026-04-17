package com.itheima.controller;

import com.itheima.pojo.Comment;
import com.itheima.service.CommentService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/comment/list")
public class CommentListServlet extends HttpServlet {

    private CommentService commentService = new CommentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException  {
        resp.setContentType("text/plain;charset=UTF-8");
        try{
            Long videoId=Long.parseLong(req.getParameter("videoId"));

                List<Comment> commentList;
                commentList=commentService.getComment(videoId);
            for (Comment comment : commentList) {
                printComment(comment, resp.getWriter(), 0);
            }
        }catch (RuntimeException e){
            e.printStackTrace();
            resp.getWriter().write("查询失败：" + e.getMessage());
        }
    }

    private void printComment(Comment c, PrintWriter out, int level) {
        String indent = "  ".repeat(level);//缩进

        out.write(indent + "用户：" + c.getUsername() + " 内容：" + c.getContent() + "\n");

        if (c.getChildren() != null) {
            for (Comment child : c.getChildren()) {
                printComment(child, out, level + 1);
            }
        }
    }

}
