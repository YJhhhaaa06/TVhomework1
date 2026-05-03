package com.itheima.controller;

import com.itheima.command.CommandConverter;
import com.itheima.command.UploadCommand;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.pojo.UploadResult;
import com.itheima.service.ContentService;
import com.itheima.service.FileUploadService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@WebServlet("/upload/*")
@MultipartConfig(
        maxFileSize = 50 * 1024 * 1024, // 50MB
        maxRequestSize = 100 * 1024 * 1024
)
public class UploadController extends HttpServlet {
    private FileUploadService fileUploadService=new FileUploadService();
    private ContentService contentService=ContentService.getInstance();



    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
    try {
        System.out.println("enter uploadController");
        String action=req.getPathInfo();
        switch (action){
            case("/video"):
                saveVideo(req,resp);
                break;
            case ("/post"):
                savePost(req,resp);
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND,"未识别功能");
        }
    }catch (BusinessException e){
        e.printStackTrace();
        BaseServletUtil.writeError(resp,e.getCode(),e.getMessage());
    }catch (Exception e){
        e.printStackTrace();
        BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,"服务器异常，请重试");
    }



    }

    private void saveVideo(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Long userId = (Long) req.getAttribute("userId");
        String title = req.getParameter("title");
        String description=req.getParameter("description");
        String categoryId=req.getParameter("categoryId");
        Part videoPart = req.getPart("video");
        Part coverPart = req.getPart("cover");

        UploadResult videoResult=null;
        UploadResult coverResult=null;
        try{
            UploadCommand uc= CommandConverter.uploadVideoToCommand(title,description,categoryId,userId);
            videoResult= fileUploadService.saveFile(videoPart,UploadType.VIDEO);//"/upload/video/fileName"
            coverResult= fileUploadService.saveFile(coverPart,UploadType.COVER);//"/upload/cover/fileName"
            contentService.addVideo(uc,videoResult.getUrl(),coverResult.getUrl());
            BaseServletUtil.writeSuccess(resp,"上传成功");

        }catch (BusinessException e){
            BaseServletUtil.writeError(resp,e.getCode(),e.getMessage());
            if(videoResult!=null){
                fileUploadService.deleteFileQuietly(videoResult.getAbsolutePath());
            }
            if(coverResult!=null){
                fileUploadService.deleteFileQuietly(coverResult.getAbsolutePath());
            }
        }catch (Exception e){
            BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,"服务器异常，请重试");
            if(videoResult!=null){
                fileUploadService.deleteFileQuietly(videoResult.getAbsolutePath());
            }
            if(coverResult!=null){
                fileUploadService.deleteFileQuietly(coverResult.getAbsolutePath());
            }
        }
    }

    private void savePost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Long userId = (Long) req.getAttribute("userId");
        String title = req.getParameter("title");
        String description = req.getParameter("description");
        String categoryId = req.getParameter("categoryId");
        Part coverPart = req.getPart("cover");

        // 收集所有图片 part
        List<Part> imageParts = new ArrayList<>();
        for (Part part : req.getParts()) {
            if ("image".equals(part.getName()) && part.getSize() > 0) {
                imageParts.add(part);
            }
        }

        List<String> savedPaths = new ArrayList<>();
        String coverUrl = null;
        List<String> imageUrls = new ArrayList<>();

        try {
            UploadCommand uc = CommandConverter.uploadPostToCommand(title, description, categoryId, userId);

            // 保存封面（可选）
            if (coverPart != null && coverPart.getSize() > 0) {
                UploadResult coverResult = fileUploadService.saveFile(coverPart, UploadType.COVER);
                coverUrl = coverResult.getUrl();
                savedPaths.add(coverResult.getAbsolutePath());
            }

            // 保存图片（可有0~多张）
            for (Part imagePart : imageParts) {
                UploadResult imageResult = fileUploadService.saveFile(imagePart, UploadType.IMAGE);
                imageUrls.add(imageResult.getUrl());
                savedPaths.add(imageResult.getAbsolutePath());
            }

            contentService.addPost(uc, coverUrl, imageUrls);
            BaseServletUtil.writeSuccess(resp, "上传成功");

        } catch (BusinessException e) {
            e.printStackTrace();
            for (String path : savedPaths) {
                fileUploadService.deleteFileQuietly(path);
            }
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            for (String path : savedPaths) {
                fileUploadService.deleteFileQuietly(path);
            }
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }







}
