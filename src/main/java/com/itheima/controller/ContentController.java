package com.itheima.controller;

import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.service.ContentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 内容管理接口（作者本人操作）。
 *
 * 当前实现：
 * - POST /content/commentEnabled?contentId=X&amp;enabled=0|1  作者开关自己作品的评论区
 *
 * 后续阶段（四/六）的删除、编辑接口同域扩展（AuthFilter 对登录/所有权外校验逐接口加精确路径）。
 */
@WebServlet("/content/*")
public class ContentController extends BaseServlet {

    @Inject
    private ContentService contentService;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/commentEnabled".equals(action)) {
            setCommentEnabled(req, resp);
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }

    /** 作者开关评论区：POST /content/commentEnabled?contentId=X&enabled=0|1（仅登录，AuthFilter 精确保护） */
    private void setCommentEnabled(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long contentId = parseContentId(req, resp);
        if (contentId == null) {
            return;
        }
        String enabledStr = req.getParameter("enabled");
        if (enabledStr == null || enabledStr.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "enabled不能为空");
            return;
        }
        Boolean enabled = parseEnabled(enabledStr);
        if (enabled == null) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "enabled格式错误，应为 0/1 或 true/false");
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        contentService.setCommentEnabled(contentId, userId, enabled);
        BaseServletUtil.writeSuccess(resp, "操作成功");
    }

    private Long parseContentId(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String contentIdStr = req.getParameter("contentId");
        if (contentIdStr == null || contentIdStr.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "contentId不能为空");
            return null;
        }
        try {
            return Long.parseLong(contentIdStr);
        } catch (NumberFormatException e) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "contentId格式错误");
            return null;
        }
    }

    private Boolean parseEnabled(String value) {
        switch (value.trim().toLowerCase()) {
            case "1":
            case "true":
                return Boolean.TRUE;
            case "0":
            case "false":
                return Boolean.FALSE;
            default:
                return null;
        }
    }
}