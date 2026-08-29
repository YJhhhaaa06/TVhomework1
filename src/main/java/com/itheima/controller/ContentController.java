package com.itheima.controller;

import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.service.ContentService;
import com.itheima.service.FileUploadService;
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
 * - POST /content/update?contentId=X&amp;title=&amp;description=  作者编辑标题/简介（A3）
 * - POST /content/mediaDelete?contentId=X&amp;type=&amp;sort=  作者删除单条媒体（单图删除）
 *
 * 后续阶段（四/六）的删除接口同域扩展（AuthFilter 对登录/所有权外校验逐接口加精确路径）。
 */
@WebServlet("/content/*")
public class ContentController extends BaseServlet {

    @Inject
    private ContentService contentService;
    @Inject
    private FileUploadService fileUploadService;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if ("/commentEnabled".equals(action)) {
            setCommentEnabled(req, resp);
        } else if ("/update".equals(action)) {
            updateContentInfo(req, resp);
        } else if ("/mediaDelete".equals(action)) {
            deleteMedia(req, resp);
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

    /** 作者编辑标题/简介：POST /content/update?contentId=X&title=&description=（仅登录，AuthFilter 精确保护） */
    private void updateContentInfo(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long contentId = parseContentId(req, resp);
        if (contentId == null) {
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        String title = req.getParameter("title");
        String description = req.getParameter("description");
        contentService.updateContentInfo(contentId, userId, title, description);
        BaseServletUtil.writeSuccess(resp, "操作成功");
    }

    /** 作者删除单条媒体：POST /content/mediaDelete?contentId=X&type=&sort=（仅登录，AuthFilter 精确保护） */
    private void deleteMedia(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long contentId = parseContentId(req, resp);
        if (contentId == null) {
            return;
        }
        int type = parseMediaType(req, resp);
        if (type < 0) {
            return;
        }
        int sort = parseMediaSort(req, resp);
        if (sort < 0) {
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        String oldUrl = contentService.deleteMedia(contentId, userId, type, sort);
        // 旧文件清理（尽力而为，DB 已提交）
        fileUploadService.deleteFileByUrl(oldUrl);
        BaseServletUtil.writeSuccess(resp, "删除成功");
    }

    private int parseMediaType(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String value = req.getParameter("type");
        if (value == null || value.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "type不能为空");
            return -1;
        }
        try {
            int type = Integer.parseInt(value);
            if (type < 1 || type > 3) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "type格式错误，应为 1/2/3");
                return -1;
            }
            return type;
        } catch (NumberFormatException e) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "type格式错误");
            return -1;
        }
    }

    private int parseMediaSort(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String value = req.getParameter("sort");
        if (value == null || value.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "sort不能为空");
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "sort格式错误");
            return -1;
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