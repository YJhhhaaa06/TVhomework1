package com.itheima.controller;

import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.AdminContentVO;
import com.itheima.service.ContentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * 运维接口：内容审核下架/恢复。仅管理员可访问（AuthFilter 对 /api/admin/* 校验 role==1，非管理员 403）。
 *
 * - GET  /api/admin/content/list    管理端内容清单（含正常与已下架）
 * - POST /api/admin/content/hide?contentId=X    下架内容（is_deleted 0→2）
 * - POST /api/admin/content/unhide?contentId=X  恢复内容（is_deleted 2→0）
 */
@WebServlet("/api/admin/content/*")
public class AdminContentController extends BaseServlet {

    @Inject
    private ContentService contentService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/list".equals(action)) {
            List<AdminContentVO> list = contentService.listContentForAdmin();
            BaseServletUtil.writeSuccess(resp, list);
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/hide".equals(action)) {
            Long contentId = parseContentId(req, resp);
            if (contentId == null) return;
            contentService.hideContent(contentId);
            BaseServletUtil.writeSuccess(resp, "下架成功");
        } else if ("/unhide".equals(action)) {
            Long contentId = parseContentId(req, resp);
            if (contentId == null) return;
            contentService.unhideContent(contentId);
            BaseServletUtil.writeSuccess(resp, "恢复成功");
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
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
}
