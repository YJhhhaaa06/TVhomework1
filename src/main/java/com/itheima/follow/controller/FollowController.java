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

        switch (action) {
            // T7：显式区分"是否传了分页参数"——**缺省（都不传）维持全量返回**，与改造前逐字节一致
            // （既有调用方与 pytest 用例零破坏；不能用"默认 page=1&pageSize=50"代替，上限 50 会截断全量）；
            // 传任一分页参数 → 分页信封（A1 有序窗口）。分页解析复用 T5 公共方法（默认 1/10、上限 50）。
            case "/following":
                if (hasPagingParams(req)) {
                    BaseServletUtil.writeSuccess(resp, followService.getFollowingList(userId, currentUserId,
                            BaseServletUtil.parsePage(req), BaseServletUtil.parsePageSize(req)));
                } else {
                    BaseServletUtil.writeSuccess(resp, followService.getFollowingList(userId, currentUserId));
                }
                break;
            case "/followers":
                if (hasPagingParams(req)) {
                    BaseServletUtil.writeSuccess(resp, followService.getFollowerList(userId, currentUserId,
                            BaseServletUtil.parsePage(req), BaseServletUtil.parsePageSize(req)));
                } else {
                    BaseServletUtil.writeSuccess(resp, followService.getFollowerList(userId, currentUserId));
                }
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
        }
    }

    /**
     * 是否显式传了分页参数（T7 缺省兼容判据）：{@code page} 与 {@code pageSize} **任一**出现即视为分页请求。
     *
     * <p>不用"默认值是否等于 1/10"来判断——那无法区分"没传"与"显式传 page=1&pageSize=10"，
     * 也就无法保留"不传 → 全量返回"的既有行为。
     */
    private boolean hasPagingParams(HttpServletRequest req) {
        return req.getParameter("page") != null || req.getParameter("pageSize") != null;
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
