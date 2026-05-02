package com.itheima.controller;

import com.itheima.pojo.Comment;
import com.itheima.service.CommentService;
import com.itheima.util.ErrorCodeUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

@WebServlet("/comment")
public class CommentServlet extends HttpServlet {
    private CommentService commentService = new CommentService();

    private BaseServletUtil baseServletUtil =new BaseServletUtil();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException  {
        try{
            String action=req.getParameter("action");
            if(action==null||action.trim().isEmpty()){
                baseServletUtil.writeError(resp, ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
                return;
            }
            switch (action){
                case "add":
                    addComment(req,resp);
                    break;
                case "show":
                    showComment(req,resp);
                    break;
                default:
                    baseServletUtil.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"未识别功能");
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            baseServletUtil.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,e.getMessage());
        }catch (Exception ex){
            baseServletUtil.writeError(resp,ErrorCodeUtil.SYSTEM_ERROR,ex.getMessage());
        }


    }




    protected void addComment(HttpServletRequest req, HttpServletResponse resp)throws Exception{
        resp.setContentType("text/plain;charset=UTF-8");
        try{
            Long videoId=Long.parseLong(req.getParameter("videoId"));
            Long userId = (Long) req.getAttribute("userId");
            String content = req.getParameter("content");
            String parentIdStr =req.getParameter("parentId");
            Long parentId = null;
            if (parentIdStr != null && !parentIdStr.isEmpty()) {
                parentId = Long.parseLong(parentIdStr);
            }
            commentService.addComment(userId,videoId,parentId,content);
            baseServletUtil.writeSuccess(resp,"评论成功");
        }catch (Exception e){
            e.printStackTrace();
            throw e;
        }
    }


    protected void showComment(HttpServletRequest req, HttpServletResponse resp)throws Exception {
        resp.setContentType("text/plain;charset=UTF-8");
        Long videoId=Long.parseLong(req.getParameter("videoId"));

        List<Comment> commentList;
        commentList=commentService.getComment(videoId);
        baseServletUtil.writeSuccess(resp,commentList);
//        for (Comment comment : commentList) {
//            printComment(comment, resp.getWriter(), 0);
//        }

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
