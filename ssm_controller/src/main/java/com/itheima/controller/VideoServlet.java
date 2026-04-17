package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.pojo.VideoDetail;
import com.itheima.service.VideoService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@WebServlet("/video")
public class VideoServlet extends HttpServlet {
    private ObjectMapper mapper = new ObjectMapper();

    private VideoService vs=new VideoService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)throws IOException{
        Map<String, Object> result = new HashMap<>();
        resp.setContentType("application/json;charset=UTF-8");
        try{
            String idStr=req.getParameter("videoId");

            if(idStr==null||idStr.trim().isEmpty()){//输入为空
                result.put("code",1);
                result.put("msg","EMPTY_INPUT");
                mapper.writeValue(resp.getWriter(), result);
                return;
            }

            Long videoId;
            try {
                videoId = Long.parseLong(idStr.trim());//将输入转为整数
            } catch (NumberFormatException e) {
                result.put("code",1);
                result.put("msg","VIDEO_ID_INVALID");
                mapper.writeValue(resp.getWriter(), result);
                return;
            }
            com.itheima.pojo.VideoDetail vd =vs.getVideoDetail(videoId);
            if(vd==null){//找不到视频
                result.put("code",1);
                result.put("msg","FAIL_TO_FIND_VIDEO");
                mapper.writeValue(resp.getWriter(),result);
                return;
            }

            result.put("code",0);
            result.put("msg","success");
            result.put("data",vd);
            mapper.writeValue(resp.getWriter(),result);

        } catch (RuntimeException e) {
            e.printStackTrace();
            result.put("code",2);
            result.put("msg",e.getMessage());
            mapper.writeValue(resp.getWriter(),result);
        }

    }

}
