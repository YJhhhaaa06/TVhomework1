package com.itheima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.exception.ParamException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.stream.Collectors;

public class RequestParser {
    private static final ObjectMapper mapper = new ObjectMapper();
    public static String getBody(HttpServletRequest request) throws IOException {
        return request.getReader()
                .lines()
                .collect(Collectors.joining());
    }

    public static <T> T parse(HttpServletRequest req, Class<T> clazz) {


        try {
            String json = getBody(req);
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new ParamException("请求格式错误");
        }
    }
}
