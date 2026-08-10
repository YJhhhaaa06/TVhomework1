package com.itheima.controller;

import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.ContentVO;
import com.itheima.service.ContentCacheManager;
import com.itheima.service.ContentStatusFiller;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/start")
public class StartController extends BaseServlet {
    @Inject
    private ContentStatusFiller contentStatusFiller;
    @Inject
    private ContentCacheManager contentCacheManager;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json;charset=utf-8");

        Integer type = parseParam(req.getParameter("type"));
        Integer categoryId = parseParam(req.getParameter("categoryId"));

        List<ContentVO> recommend = contentCacheManager.getRecommendByFilter(type, categoryId, 12);

        Long userId = (Long) req.getAttribute("userId");
        contentStatusFiller.fillLikeAndFollowBatch(recommend, userId);

        BaseServletUtil.writeSuccess(resp, recommend);
    }

    private Integer parseParam(String value) {
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
