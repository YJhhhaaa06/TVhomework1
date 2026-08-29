package com.itheima.controller;

import com.itheima.model.command.CommandConverter;
import com.itheima.model.command.UploadCommand;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ParamException;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.UploadResult;
import com.itheima.service.ContentService;
import com.itheima.service.FileUploadService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@WebServlet("/api/upload/*")
@MultipartConfig(
        maxFileSize = 50 * 1024 * 1024, // 50MB
        maxRequestSize = 100 * 1024 * 1024
)
public class UploadController extends BaseServlet {
    @Inject
    private FileUploadService fileUploadService;
    @Inject
    private ContentService contentService;



    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action=req.getPathInfo();
        if(action==null){
            BaseServletUtil.writeError(resp,ErrorCode.NOT_FOUND,"未识别功能");
            return;
        }
        switch (action){
            case("/video"):
                saveVideo(req,resp);
                break;
            case ("/post"):
                savePost(req,resp);
                break;
            case ("/replace"):
                replaceMedia(req,resp);
                break;
            default:
                BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND,"未识别功能");
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
            long contentId = contentService.addVideo(uc,videoResult.getUrl(),coverResult.getUrl());
            BaseServletUtil.writeSuccess(resp, Map.of("contentId", contentId));

        }catch (BusinessException e){
            if(videoResult!=null){
                fileUploadService.deleteFileQuietly(videoResult.getAbsolutePath());
            }
            if(coverResult!=null){
                fileUploadService.deleteFileQuietly(coverResult.getAbsolutePath());
            }
            throw e;
        }catch (Exception e){
            if(videoResult!=null){
                fileUploadService.deleteFileQuietly(videoResult.getAbsolutePath());
            }
            if(coverResult!=null){
                fileUploadService.deleteFileQuietly(coverResult.getAbsolutePath());
            }
            throw new ServletException(e);
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

            long contentId = contentService.addPost(uc, coverUrl, imageUrls);
            BaseServletUtil.writeSuccess(resp, Map.of("contentId", contentId));

        } catch (BusinessException e) {
            for (String path : savedPaths) {
                fileUploadService.deleteFileQuietly(path);
            }
            throw e;
        } catch (Exception e) {
            for (String path : savedPaths) {
                fileUploadService.deleteFileQuietly(path);
            }
            throw new ServletException(e);
        }
    }

    /**
     * 作者换源：POST /api/upload/replace?contentId=&type=&sort= + file（仅登录，AuthFilter 前缀保护；所有权由 Service 校验）。
     * type ∈ 1(视频)/2(图片)/3(封面)；type/sort 定位 content_media 记录。
     */
    private void replaceMedia(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Long userId = (Long) req.getAttribute("userId");
        long contentId = parseLongParam(req, "contentId");
        int type = parseIntParam(req, "type");
        int sort = parseIntParam(req, "sort");
        UploadType uploadType = UploadType.fromMediaType(type);
        if (uploadType == null) {
            throw new ParamException("type 不合法，应为 1/2/3");
        }
        Part file = req.getPart("file");
        if (file == null || file.getSize() <= 0) {
            throw new ParamException("请选择要替换的文件");
        }

        UploadResult result = null;
        try {
            result = fileUploadService.saveFile(file, uploadType);
            String oldUrl = contentService.replaceMedia(contentId, userId, type, sort, result.getUrl());
            // 旧文件清理（尽力而为，DB 已提交）
            fileUploadService.deleteFileByUrl(oldUrl);
            BaseServletUtil.writeSuccess(resp, Map.of("url", result.getUrl()));
        } catch (BusinessException e) {
            if (result != null) {
                fileUploadService.deleteFileQuietly(result.getAbsolutePath());
            }
            throw e;
        } catch (Exception e) {
            if (result != null) {
                fileUploadService.deleteFileQuietly(result.getAbsolutePath());
            }
            throw new ServletException(e);
        }
    }

    private long parseLongParam(HttpServletRequest req, String name) {
        String value = req.getParameter(name);
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            throw new ParamException(name + " 格式错误");
        }
    }

    private int parseIntParam(HttpServletRequest req, String name) {
        String value = req.getParameter(name);
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            throw new ParamException(name + " 格式错误");
        }
    }
}
