package com.itheima.controller;

import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.service.CommentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 运维接口：评论删除。仅管理员可访问（AuthFilter 对 /api/admin/* 校验 role==1，非管理员 403）。
 */
@WebServlet("/api/admin/comment/*")
public class AdminCommentController extends BaseServlet {

    @Inject
    private CommentService commentService;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/delete".equals(action)) {
            String commentIdStr = req.getParameter("commentId");
            if (commentIdStr == null || commentIdStr.isEmpty()) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "commentId不能为空");
                return;
            }
            long commentId;
            try {
                commentId = Long.parseLong(commentIdStr);
            } catch (NumberFormatException e) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "commentId格式错误");
                return;
            }
            commentService.deleteCommentByAdmin(commentId);
            BaseServletUtil.writeSuccess(resp, "删除成功");
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }
}