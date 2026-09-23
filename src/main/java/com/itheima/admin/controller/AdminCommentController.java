package com.itheima.admin.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.comment.service.CommentService;
import com.itheima.util.AuditLog;
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
            // 审计（T8）：成功路径留痕——操作者取 LoginFilter 放入的 userId attribute（恒为 Long）：
            // AuthFilter 对 /api/admin 已先判非空、且自身也做同款 (Long) 强转，故类型漂移会先在 filter 层
            // 暴露（与全仓各 controller 同款直取，不另加 instanceof 分支）；写失败由 AuditLog 吞掉降级
            AuditLog.success("admin.comment.delete", (Long) req.getAttribute("userId"), "commentId:" + commentId);
            BaseServletUtil.writeSuccess(resp, "删除成功");
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }
}