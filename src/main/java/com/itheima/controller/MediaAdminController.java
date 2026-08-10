package com.itheima.controller;

import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.audit.MediaAuditResult;
import com.itheima.model.audit.RestoreResult;
import com.itheima.service.MediaAuditService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;

/**
 * 运维接口：媒体资源扫描与恢复。仅管理员可访问（AuthFilter 校验 role==1）。
 */
@WebServlet("/api/admin/media/*")
@MultipartConfig(
        maxFileSize = 50 * 1024 * 1024,
        maxRequestSize = 100 * 1024 * 1024
)
public class MediaAdminController extends BaseServlet {

    @Inject
    private MediaAuditService mediaAuditService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/me".equals(action)) {
            BaseServletUtil.writeSuccess(resp, true);
        } else if ("/list".equals(action)) {
            MediaAuditResult result = mediaAuditService.scanAll();
            BaseServletUtil.writeSuccess(resp, result);
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/scan".equals(action)) {
            MediaAuditResult result = mediaAuditService.scanAll();
            BaseServletUtil.writeSuccess(resp, result);
        } else if ("/restore".equals(action)) {
            long mediaId = parseMediaId(req);
            Part part = req.getPart("file");
            RestoreResult result = mediaAuditService.restoreMedia(mediaId, part);
            BaseServletUtil.writeSuccess(resp, result);
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
        }
    }

    private long parseMediaId(HttpServletRequest req) {
        String param = req.getParameter("mediaId");
        if (param == null || param.isBlank()) {
            throw new ParamException("mediaId 格式错误");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("mediaId 格式错误");
        }
    }
}
