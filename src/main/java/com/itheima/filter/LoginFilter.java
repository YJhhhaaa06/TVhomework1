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
//                HttpServletRequest req = (HttpServletRequest) request;
//                HttpServletResponse resp=(HttpServletResponse) response;
//
//                String uri = req.getRequestURI();
//                String contextPath = req.getContextPath(); // /MyAPP
//                String path = uri.substring(contextPath.length()); // 去掉项目名
//
//                String action = req.getParameter("action");
//
//
//// 👇 放行视频
//                if (uri.contains("/video") || uri.contains("/start")||uri.contains("/detail")) {
//                    chain.doFilter(req, resp);
//                    return;
//                }
//
//// 👇 放行登录注册
//                if (uri.contains("/user/login") || uri.contains("/user/register")) {
//                    chain.doFilter(req, resp);
//                    return;
//                }
//
//// 👇 放行“查看评论”
//                if (uri.contains("/comment") && "show".equals(action)) {
//                    chain.doFilter(req, resp);
//                    return;
//                }
//
//// 👇 下面才需要登录
//                String token = req.getHeader("userToken");
//
//                if (token == null || !tokenService.isTokenLegal(token)) {
//                    resp.setContentType("application/json;charset=UTF-8");
//                    resp.getWriter().write("{\"code\":401,\"msg\":\"NOT_LOGIN\"}");
//                    return;
//                }
//
//                chain.doFilter(req, resp);

//        HttpServletRequest req = (HttpServletRequest) request;
//        HttpServletResponse resp=(HttpServletResponse) response;
//
//        String path = req.getRequestURI();
//
//
//
//        if(path.contains("/video")||path.contains("/start")){
//            chain.doFilter(request,response);
//
//                }
//        // 放行不需要登录的接口
//        if (path.contains("/user")|| path.contains("/login")
//                || path.endsWith(".html")) {
//            chain.doFilter(request, response);//放行
//            return;
//        }
//
//
//        String token= req.getHeader("userToken");
//        if(tokenService.isTokenLegal(token)){
//            long userId=tokenService.getUserId(token);
//            req.setAttribute("userId", userId);
//            chain.doFilter(req, resp);
//            return;
//        }
//        writeError(resp,"未登录或登录已过期");

//            }


//}
//                @WebFilter("/*")
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


@WebFilter("/*")
public class LoginFilter implements Filter {

    private TokenService tokenService = new TokenService();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;

        String uri = req.getRequestURI();
        String contextPath = req.getContextPath(); // /MyAPP
        String path = uri.substring(contextPath.length()); // /video
        String action = req.getParameter("action");
        System.out.println("request path: " + path + " | action: " + action);


        // ===== 1. 静态资源直接放行 =====
        if (path.endsWith(".html") || path.endsWith(".css") || path.endsWith(".js")) {
            chain.doFilter(request, response);
            return;
        }

        // ===== 2. 登录/注册接口 =====
        if ("/user/login".equals(path) || "/user/register".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        // ===== 3. 视频接口（全部放行）=====
        if ("/video".equals(path) || "/start".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        // ===== 4. 评论查看（允许未登录）=====
        if ("/comment".equals(path) && "show".equals(action)) {
            chain.doFilter(request, response);
            return;
        }

        // ===== 5. 下面必须登录 =====
        String token = req.getHeader("token");

        if (token == null || !tokenService.isTokenLegal(token)) {
            writeError(response, "未登录或登录已失效");
            return;
        }

        long userId = tokenService.getUserId(token);
        req.setAttribute("userId", userId);

        chain.doFilter(request, response);
    }

    private void writeError(ServletResponse response, String msg) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\"}");
    }
}





