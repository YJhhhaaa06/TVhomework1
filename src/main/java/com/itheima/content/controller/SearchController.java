package com.itheima.content.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.common.model.dto.PageResult;
import com.itheima.content.model.dto.SearchDTO;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.service.ContentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/search/*")
public class SearchController extends BaseServlet {
    /** T19：search 域 pageSize 上限（原为该域"无上限"的唯一分页入口，公共归一未覆盖）。 */
    private static final int SEARCH_PAGE_SIZE_MAX = 100;

    /** T19：search 域**信封大小**——前端只传 `page` 时后端返回的条数（原为硬编码缺省 12）。 */
    private static final int SEARCH_PAGE_SIZE_DEFAULT = 100;

    @Inject
    private ContentService contentService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if (action == null) {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别功能");
            return;
        }
        switch (action) {
            case "/IdSearch":
                getContentDetailById(req, resp);
                break;
            case "/keywordSearch":
                search(req, resp);
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "未识别功能");
        }
    }

    protected void getContentDetailById(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SearchDTO dto;
        if (req.getContentLength() <= 0) {
            dto = new SearchDTO();
            String contentIdStr = req.getParameter("contentId");
            if (contentIdStr != null && !contentIdStr.isEmpty()) {
                dto.setContentId(Long.parseLong(contentIdStr));
            }
        } else {
            dto = RequestParser.parse(req, SearchDTO.class);
        }
        Long userId = (Long) req.getAttribute("userId");
        if(dto==null||(dto.getContentId()==null)){
            BaseServletUtil.writeError(resp,ErrorCode.PARAM_ERROR,"contentId不能为空");
            return;
        }
        ContentDetailVO cdVO = contentService.getContentDetailVO(dto.getContentId(), userId);
        if (cdVO == null) {
            BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "找不到对应内容");
            return;
        }
        BaseServletUtil.writeSuccess(resp, cdVO);
    }

    protected void search(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SearchDTO dto;
        if (req.getContentLength() <= 0) {
            dto = new SearchDTO();
            dto.setKeyword(req.getParameter("keyword"));
            // T19：GET 分支不再手写 Integer.parseInt（非法值会抛 NumberFormatException → 500），
            // 统一走公共归一（域级上限 + 域级信封；非法/缺省一律回落域级缺省）
            dto.setPage(BaseServletUtil.parsePage(req));
            dto.setPageSize(BaseServletUtil.parsePageSize(req, SEARCH_PAGE_SIZE_MAX, SEARCH_PAGE_SIZE_DEFAULT));
        } else {
            dto = RequestParser.parse(req, SearchDTO.class);
        }

        if (dto == null || dto.getKeyword() == null || dto.getKeyword().isBlank()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "输入不能为空");
            return;
        }
        // T19：两条分支同一口径——JSON body 分支拿不到 request parameter，走 request 无关的 normalize*
        // （唯一源仍是 BaseServletUtil）；原"缺省 12 / 无上限"改由域级常量决定。
        // （GET 分支的值已经归一过，normalize* 幂等，再走一遍只为两条分支共享同一出口）
        int page = BaseServletUtil.normalizePage(dto.getPage());
        int pageSize = BaseServletUtil.normalizePageSize(dto.getPageSize(),
                SEARCH_PAGE_SIZE_MAX, SEARCH_PAGE_SIZE_DEFAULT);
        Long userId = (Long) req.getAttribute("userId");
        PageResult<ContentVO> result = contentService.search(dto.getKeyword().trim(), userId, page, pageSize);
        BaseServletUtil.writeSuccess(resp, result);
    }
}
