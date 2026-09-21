package com.itheima.content.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.common.model.dto.PageResult;
import com.itheima.ioc.annotation.Inject;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.service.FeedService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/feed")
public class FeedController extends BaseServlet {
    /** T19：feed 域 pageSize 上限（公共 cap 50 已随 T19 删除，各域自持常量；镜像 follow/comment 先例）。 */
    private static final int FEED_PAGE_SIZE_MAX = 100;

    /** T19：feed 域**信封大小**——前端只传 `page` 时后端返回的条数（原公共缺省 10）。 */
    private static final int FEED_PAGE_SIZE_DEFAULT = 100;

    @Inject
    private FeedService feedService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Long currentUserId = (Long) req.getAttribute("userId");
        int page = BaseServletUtil.parsePage(req);
        int pageSize = BaseServletUtil.parsePageSize(req, FEED_PAGE_SIZE_MAX, FEED_PAGE_SIZE_DEFAULT);

        PageResult<ContentVO> result = feedService.getFeed(currentUserId, page, pageSize);
        BaseServletUtil.writeSuccess(resp, result);
    }
}
