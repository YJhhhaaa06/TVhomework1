package com.itheima.controller;

import com.itheima.DTO.SearchDTO;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.factory.BeanFactory;
import com.itheima.pojo.ContentDetailVO;
import com.itheima.pojo.ContentVO;
import com.itheima.service.ContentService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/search/*")
public class SearchController extends HttpServlet {
    private ContentService contentService = BeanFactory.getContentService();

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
        SearchDTO dto = RequestParser.parse(req, SearchDTO.class);
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
        SearchDTO dto = RequestParser.parse(req, SearchDTO.class);

        if (dto == null || dto.getKeyword() == null || dto.getKeyword().isBlank()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "输入不能为空");
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        List<ContentVO> list = contentService.search(dto.getKeyword().trim(), userId);
        BaseServletUtil.writeSuccess(resp, list);
    }

    protected void getDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        SearchDTO dto = RequestParser.parse(req, SearchDTO.class);
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