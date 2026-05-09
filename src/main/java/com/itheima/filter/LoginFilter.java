package com.itheima.filter;

import com.itheima.controller.BaseServletUtil;
import com.itheima.exception.ErrorCode;
import com.itheima.util.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class LoginFilter implements Filter {


    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String token = req.getHeader("token");
        if(token==null){
            req.setAttribute("userId", null);
        } else if (!JwtUtil.isTokenValid(token)) {
            BaseServletUtil.writeError(resp, ErrorCode.UNAUTHORIZED,"token不存在或已过期");
            return;
        }else {
            req.setAttribute("userId",JwtUtil.getUserId(token));
        }


        chain.doFilter(request, response);
    }

}
