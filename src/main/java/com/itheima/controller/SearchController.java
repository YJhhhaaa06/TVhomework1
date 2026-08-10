package com.itheima.controller;

import com.itheima.model.dto.PageResult;
import com.itheima.model.dto.SearchDTO;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.service.ContentService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/search/*")
public class SearchController extends BaseServlet {
    @Inject
    private ContentService contentService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
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
                case "/getDetailRecommended":
                    getDetail(req, resp);
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "未识别功能");
            }
        } catch (BusinessException e) {
            e.printStackTrace();
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception ex) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, ex.getMessage());
        }
    }

    protected void getContentDetailById(HttpServletRequest req, HttpServletResponse resp) throws Exception {
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

    protected void search(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        SearchDTO dto;
        if (req.getContentLength() <= 0) {
            dto = new SearchDTO();
            dto.setKeyword(req.getParameter("keyword"));
            String pageStr = req.getParameter("page");
            String pageSizeStr = req.getParameter("pageSize");
            if (pageStr != null && !pageStr.isEmpty()) {
                dto.setPage(Integer.parseInt(pageStr));
            }
            if (pageSizeStr != null && !pageSizeStr.isEmpty()) {
                dto.setPageSize(Integer.parseInt(pageSizeStr));
            }
        } else {
            dto = RequestParser.parse(req, SearchDTO.class);
        }

        if (dto == null || dto.getKeyword() == null || dto.getKeyword().isBlank()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "输入不能为空");
            return;
        }
        int page = dto.getPage() != null ? dto.getPage() : 1;
        int pageSize = dto.getPageSize() != null ? dto.getPageSize() : 12;
        Long userId = (Long) req.getAttribute("userId");
        PageResult<ContentVO> result = contentService.search(dto.getKeyword().trim(), userId, page, pageSize);
        BaseServletUtil.writeSuccess(resp, result);
    }

    protected void getDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
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
}
