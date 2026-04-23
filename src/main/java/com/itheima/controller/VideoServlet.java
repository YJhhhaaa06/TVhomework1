package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.pojo.Video;
import com.itheima.service.VideoService;
import com.itheima.util.ErrorCodeUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/video")
public class VideoServlet extends HttpServlet {
    private ObjectMapper mapper = new ObjectMapper();
    private VideoService vs=VideoService.getInstance();
    private BaseServlet baseServlet=new BaseServlet();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)throws IOException{
        try{
            String action=req.getParameter("action");
            if(action==null||action.trim().isEmpty()){
                baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
                return;
            }
            switch (action){
                case "IdSearch":
                    getVideoDetailById(req,resp);
                    break;
                case "keywordSearch":
                    search(req,resp);
                    break;
                default:
                    baseServlet.writeError(resp,ErrorCodeUtil.PARAM_ERROR,"未识别功能");
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            baseServlet.writeError(resp,ErrorCodeUtil.BUSINESS_ERROR,e.getMessage());
        }catch (Exception ex){
            baseServlet.writeError(resp,ErrorCodeUtil.SYSTEM_ERROR,ex.getMessage());
        }


    }

    protected void getVideoDetailById(HttpServletRequest req, HttpServletResponse resp)throws Exception{
            Long videoId=baseServlet.getLong(req,"videoId");
            com.itheima.pojo.VideoDetail vd =vs.getVideoDetail(videoId);
            if(vd==null){//找不到视频
                baseServlet.writeError(resp,1,"找不到对应视频");
                return;
            }
            baseServlet.writeSuccess(resp,vd);
    }
    protected void search(HttpServletRequest req, HttpServletResponse resp)throws Exception{
            String keyword=req.getParameter("keyword");
            if(keyword==null||keyword.trim().isEmpty()){
                baseServlet.writeError(resp, ErrorCodeUtil.PARAM_ERROR,"输入不能为空");
                return;
            }
            List<Video> list=vs.search(keyword.trim());
            baseServlet.writeSuccess(resp,list);
    }


}
