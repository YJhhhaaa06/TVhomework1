package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.pojo.Video;
import com.itheima.service.VideoService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/video/search")
public class VideoSearchServlet extends HttpServlet {
    Map<String, Object> result = new HashMap<>();
    ObjectMapper mapper = new ObjectMapper();
    private VideoService vs = new VideoService();
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)throws IOException {
        resp.setContentType("application/json;charset=UTF-8");

        try {
            String keyword=req.getParameter("keyword");
            keyword=keyword.trim();
            if(keyword==null||keyword.isEmpty()){
                resp.getWriter().write("输入不能为空");
                throw new RuntimeException("EMPTY_KEYWORD");
            }
            List<Video> list=vs.search(keyword);
            result.put("code",0);
            result.put("msg","success");
            result.put("data",list);

        }catch (RuntimeException e){
            e.printStackTrace();
            result.put("code",1);
            result.put("msg",e.getMessage());
        }
        mapper.writeValue(resp.getWriter(), result);
    }
}
