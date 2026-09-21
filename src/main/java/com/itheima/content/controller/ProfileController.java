package com.itheima.content.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.content.model.vo.ProfileVO;
import com.itheima.content.service.ProfileService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/profile")
public class ProfileController extends BaseServlet {
    /** T19：profile 域 pageSize 上限（公共 cap 50 已随 T19 删除，各域自持常量；镜像 follow/comment 先例）。 */
    private static final int PROFILE_PAGE_SIZE_MAX = 100;

    /** T19：profile 域**信封大小**——前端只传 `page` 时后端返回的条数（原公共缺省 10）。 */
    private static final int PROFILE_PAGE_SIZE_DEFAULT = 100;

    @Inject
    private ProfileService profileService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        long profileUserId = parseUserId(req);
        int page = BaseServletUtil.parsePage(req);
        int pageSize = BaseServletUtil.parsePageSize(req, PROFILE_PAGE_SIZE_MAX, PROFILE_PAGE_SIZE_DEFAULT);
        Long currentUserId = (Long) req.getAttribute("userId");

        ProfileVO profile = profileService.getProfile(profileUserId, currentUserId, page, pageSize);
        BaseServletUtil.writeSuccess(resp, profile);
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
}
