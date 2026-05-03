package com.itheima.service;

import com.itheima.controller.UploadType;
import com.itheima.pojo.UploadResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public class FileUploadService {

    public UploadResult saveFile(Part part, UploadType type) throws IOException {
        // 1. 校验
        validate(part, type);

        // 2. 生成文件名
        String fileName = UUID.randomUUID() + getSuffix(part);
        System.out.println("get file name:"+fileName);

        // 3. 目录
        String basePath = getBasePath();
        System.out.println("get path:"+basePath);
        File dir = new File(basePath, type.getDir());
        if (!dir.exists()) dir.mkdirs();

        // 4. 写入
        File file = new File(dir, fileName);
        part.write(file.getAbsolutePath());//绝对路径，dir目录+fileName文件名，dir=basePath+type.getPath

        UploadResult result=new UploadResult();
        result.setUrl("/upload/" + type.getDir() + "/" + fileName);
        result.setAbsolutePath(file.getAbsolutePath().replace('\\', '/'));
        return result;
    }

    public void deleteFileQuietly(String absolutePath) {
        if (absolutePath == null) return;

        try {
            File file = new File(absolutePath);
            if (file.exists()) file.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void validate(Part part, UploadType type) {//校验
        String contentType = part.getContentType();
        String fileName = part.getSubmittedFileName();

        if (contentType == null || !type.isSuffixValid(fileName)) {
            throw new RuntimeException("文件类型不支持");
        }

    }
    private String getSuffix(Part part) {
        String fileName = part.getSubmittedFileName();
        int dotIndex = fileName.lastIndexOf('.');
        return fileName.substring(dotIndex).toLowerCase();
    }
    private String getBasePath() {   // 需要把 req 传进来
        return "D:/upload";
    }
}

