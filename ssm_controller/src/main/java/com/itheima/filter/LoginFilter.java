package com.itheima.filter;

import com.itheima.service.TokenService;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

//@WebFilter("/*")
//public class LoginFilter implements Filter {
//
//    private TokenService tokenService = new TokenService();
//
//    @Override
//    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)//FilterChain用于控制要不要往下执行
//            throws IOException, ServletException {
//
//        HttpServletRequest req = (HttpServletRequest) request;
//
//        String path = req.getRequestURI();
//
//                if (path.contains("/video")
//                        || path.contains("/login")
//                        || path.endsWith(".html")) {
//                    chain.doFilter(request,response);
//                    return;
//                }
//
//        // 放行不需要登录的接口
//        if (path.contains("/user")|| path.contains("/login")
//                || path.endsWith(".html")) {
//            chain.doFilter(request, response);//放行
//            return;
//        }
//
//        String token = req.getHeader("token");
//
//
//        if(token==null||!tokenService.isTokenLegal(token)){
//            writeError(response,"未登录或登录已失效");
//            return;
//        }
//        long userId=tokenService.getUserId(token);
//
//        // 核心：把 userId 存进去
//        req.setAttribute("userId", userId);
//
//        chain.doFilter(request, response);
//    }
//
//    private void writeError(ServletResponse response, String msg) throws IOException {
//        response.setContentType("application/json;charset=UTF-8");
//        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\"}");
//    }
//}

