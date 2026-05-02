package com.itheima.controller;

import com.itheima.service.FileUploadService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

@WebServlet("/upload")
@MultipartConfig(
        maxFileSize = 50 * 1024 * 1024, // 50MB
        maxRequestSize = 100 * 1024 * 1024
)
public class UploadController extends HttpServlet {
    private FileUploadService fileUploadService=new FileUploadService();



    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        System.out.println("enter uploadController");

        Part videoPart = req.getPart("video");
        Part coverPart = req.getPart("cover");
        String title = req.getParameter("title");
        UploadType type=UploadType.fromContentType("video/");
        System.out.println(type.getSuffixes());
        fileUploadService.saveFile(videoPart,type);




    }







}
