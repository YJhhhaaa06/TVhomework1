package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.exception.ErrorCode;
import com.itheima.pojo.Content;
import com.itheima.service.ContentService;
import com.itheima.util.ErrorCodeUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/search/*")
public class SearchController extends HttpServlet {
    private ObjectMapper mapper = new ObjectMapper();
    private ContentService contentService = ContentService.getInstance();
    private BaseServletUtil baseServletUtil =new BaseServletUtil();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)throws IOException{
//        try{
//            String action=req.getParameter("action");
//            if(action==null||action.trim().isEmpty()){
//                baseServletUtil.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
//                return;
//            }
//            switch (action){
//                case "IdSearch":
//                    getVideoDetailById(req,resp);
//                    break;
//                case "keywordSearch":
//                    search(req,resp);
//                    break;
//                default:
//                    baseServletUtil.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"未识别功能");
//            }
//        } catch (RuntimeException e) {
//            e.printStackTrace();
//            baseServletUtil.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,e.getMessage());
//        }catch (Exception ex){
//            baseServletUtil.writeError(resp,ErrorCodeUtil.SYSTEM_ERROR,ex.getMessage());
//        }


    }

//    protected void getVideoDetailById(HttpServletRequest req, HttpServletResponse resp)throws Exception{
//            Long videoId= baseServletUtil.getLong(req,"videoId");
//            com.itheima.pojo.Content content = contentService.getContentById(videoId);
//            if(content==null){//找不到视频
//                baseServletUtil.writeError(resp,1,"找不到对应视频");
//                return;
//            }
//            baseServletUtil.writeSuccess(resp,content);
//    }
//    protected void search(HttpServletRequest req, HttpServletResponse resp)throws Exception{
//            String keyword=req.getParameter("keyword");
//            if(keyword==null||keyword.trim().isEmpty()){
//                baseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR,"输入不能为空");
//                return;
//            }
//            List<Content> list= contentService.search(keyword.trim());
//            baseServletUtil.writeSuccess(resp,list);
//    }


}
