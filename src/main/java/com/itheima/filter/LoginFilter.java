package com.itheima.filter;

import com.itheima.factory.BeanFactory;
import com.itheima.service.TokenService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class LoginFilter implements Filter {

    private TokenService tokenService = BeanFactory.getTokenService();

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
        if (token != null && tokenService.isTokenLegal(token)) {
            req.setAttribute("userId", tokenService.getUserId(token));
        } else {
            req.setAttribute("userId", null);
        }

        chain.doFilter(request, response);
    }
}
