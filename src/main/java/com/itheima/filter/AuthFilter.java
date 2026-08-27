package com.itheima.filter;

import com.itheima.controller.BaseServletUtil;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.IocContainer;
import com.itheima.service.UserService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

public class AuthFilter implements Filter {

    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "/api/upload",      // 上传视频/帖子
            "/api/admin",       // 运维接口（媒体/评论删除等，需 role==1）
            "/follow",      // 关注/取关
            "/like",        // 点赞/取消点赞
            "/feed"       // 关注动态

    );

    private static final Set<String> PROTECTED_EXACT = Set.of(//精确的限制登录接口
            "/comment/add",  // 发表评论
            "/comment/delete",  // 删除评论
            "/user/changePassword",  // 修改密码
            "/coupon/grab",      // 优惠券抢购
            "/coupon/my"        // 我的优惠券
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI().substring(req.getContextPath().length());//会获得http://localhost:8080/MyAPP之后的东西

        if (requiresLogin(path) && req.getAttribute("userId") == null) {
            BaseServletUtil.writeError(resp, ErrorCode.UNAUTHORIZED, "请先登录");
            return;
        }

        if (path.startsWith("/api/admin")) {
            Long userId = (Long) req.getAttribute("userId");
            UserService userService = IocContainer.getInstance().getBean(UserService.class);
            if (userId == null || !userService.isAdmin(userId)) {
                BaseServletUtil.writeError(resp, ErrorCode.FORBIDDEN, "无权限");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean requiresLogin(String path) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        for (String exact : PROTECTED_EXACT) {
            if (path.equals(exact)) return true;
        }
        return false;
    }
}
