package com.itheima.follow.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.follow.service.FollowService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/follow/*")
public class FollowController extends BaseServlet {
    /** T11-A：follow 域 pageSize 上限（大 chunk 前端承载；公共 cap 50 不动，镜像评论域 500 先例）。 */
    private static final int FOLLOW_PAGE_SIZE_MAX = 200;

    /** T11-A：follow 域信封大小——前端只传 `page` 时后端返回的条数（不再落公共默认 10）。 */
    private static final int FOLLOW_PAGE_SIZE_DEFAULT = 200;

    @Inject
    private FollowService followService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if (action == null) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
            return;
        }
        Long currentUserId = (Long) req.getAttribute("userId");
        long userId = parseUserId(req);

        // T11-A（契约变更，已获用户批准）：删除 T7 的「缺省不传参 → 全量数组」分支，缺省**归一为第一页信封**
        // ——不传参 = page 1 / pageSize 200，与显式 `page=1&pageSize=200` 响应逐字节一致；
        // 信封大小由本域常量决定，前端只传 `page`；显式 pageSize 仍受上限 200 约束（保留小信封跨页验证能力）。
        switch (action) {
            case "/following":
                BaseServletUtil.writeSuccess(resp, followService.getFollowingList(userId, currentUserId,
                        BaseServletUtil.parsePage(req),
                        BaseServletUtil.parsePageSize(req, FOLLOW_PAGE_SIZE_MAX, FOLLOW_PAGE_SIZE_DEFAULT)));
                break;
            case "/followers":
                BaseServletUtil.writeSuccess(resp, followService.getFollowerList(userId, currentUserId,
                        BaseServletUtil.parsePage(req),
                        BaseServletUtil.parsePageSize(req, FOLLOW_PAGE_SIZE_MAX, FOLLOW_PAGE_SIZE_DEFAULT)));
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if(action==null){
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR,"action不能为空");
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        long followedUserId = parseFollowedUserId(req);

        switch (action) {
            case "/add":
                followService.follow(userId, followedUserId);
                BaseServletUtil.writeSuccess(resp, "关注成功");
                break;
            case "/remove":
                followService.unfollow(userId, followedUserId);
                BaseServletUtil.writeSuccess(resp, "已取关");
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
        }
    }

    private long parseUserId(HttpServletRequest req) {
        String param = req.getParameter("userId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 userId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("userId 格式错误");
        }
    }

    private long parseFollowedUserId(HttpServletRequest req) {
        String param = req.getParameter("followedUserId");
        if (param == null || param.isBlank()) {
            throw new ParamException("缺少 followedUserId");
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            throw new ParamException("followedUserId 格式错误");
        }
    }
}
