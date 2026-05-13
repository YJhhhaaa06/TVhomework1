package com.itheima.controller;

import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.service.LikeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/like/*")
public class LikeController extends BaseServlet {
    @Inject
    private LikeService likeService;

    // ==================== 写操作 ====================

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            String action = req.getPathInfo();
            if (action == null) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
                return;
            }
            Long userId = (Long) req.getAttribute("userId");

            switch (action) {
                case "/content/add":
                    likeService.likeContent(userId, parseContentId(req));
                    BaseServletUtil.writeSuccess(resp, "点赞成功");
                    break;
                case "/content/remove":
                    likeService.removeLikeContent(userId, parseContentId(req));
                    BaseServletUtil.writeSuccess(resp, "已取消点赞");
                    break;
                case "/comment/add":
                    likeService.likeComment(userId, parseCommentId(req));
                    BaseServletUtil.writeSuccess(resp, "点赞成功");
                    break;
                case "/comment/remove":
                    likeService.removeLikeComment(userId, parseCommentId(req));
                    BaseServletUtil.writeSuccess(resp, "已取消点赞");
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
            }
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    // ==================== 读操作 ====================

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            String action = req.getPathInfo();
            if (action == null) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
                return;
            }
            Long userId = (Long) req.getAttribute("userId");

            switch (action) {
                case "/content/status":
                    boolean contentLiked = likeService.isContentLiked(userId, parseContentId(req));
                    BaseServletUtil.writeSuccess(resp, contentLiked);
                    break;
                case "/content/count":
                    int contentCount = likeService.getContentLikeCount(parseContentId(req));
                    BaseServletUtil.writeSuccess(resp, contentCount);
                    break;
                case "/comment/status":
                    boolean commentLiked = likeService.isCommentLiked(userId, parseCommentId(req));
                    BaseServletUtil.writeSuccess(resp, commentLiked);
                    break;
                case "/comment/count":
                    int commentCount = likeService.getCommentLikeCount(parseCommentId(req));
                    BaseServletUtil.writeSuccess(resp, commentCount);
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
            }
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    // ==================== 参数解析 ====================

    private long parseContentId(HttpServletRequest req) {
        String param = req.getParameter("contentId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 contentId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("contentId 格式错误");
        }
    }

    private long parseCommentId(HttpServletRequest req) {
        String param = req.getParameter("commentId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 commentId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("commentId 格式错误");
        }
    }
}