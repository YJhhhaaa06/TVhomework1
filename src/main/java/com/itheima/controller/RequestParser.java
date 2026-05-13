package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.exception.ParamException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.stream.Collectors;

public class RequestParser {
    private static final ObjectMapper mapper = new ObjectMapper();
    public static String getBody(HttpServletRequest request) throws IOException {
        return request.getReader()
                .lines()
                .collect(Collectors.joining());
    }

//    public static String getBody(HttpServletRequest req) throws IOException {
//        StringBuilder sb = new StringBuilder();
//        BufferedReader reader = req.getReader();
//        String line;
//        while ((line = reader.readLine()) != null) {
//            sb.append(line);
//        }
//        return sb.toString();
//    }
//public static String getBody(HttpServletRequest req) throws IOException {
//    int len = req.getContentLength();
//    if (len <= 0) {
//        return "";
//    }
//
//    char[] buffer = new char[len];
//    BufferedReader reader = req.getReader();
//
//    int read = 0;
//    while (read < len) {
//        int r = reader.read(buffer, read, len - read);
//        if (r == -1) break;
//        read += r;
//    }
//
//    return new String(buffer, 0, read);
//}

//    public static String getBody(HttpServletRequest req) throws IOException {
//        StringBuilder sb = new StringBuilder();
//        try (BufferedReader reader = req.getReader()) {
//            String line;
//            while ((line = reader.readLine()) != null) {
//                sb.append(line);
//            }
//        }
//        return sb.toString();
//    }


    public static <T> T parse(HttpServletRequest req, Class<T> clazz) {


        try {
            String json = getBody(req);
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new ParamException("请求格式错误");
        }
    }
}
