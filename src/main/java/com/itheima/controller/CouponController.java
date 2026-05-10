package com.itheima.controller;

import com.itheima.DTO.GrabCouponRequest;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.factory.BeanFactory;
import com.itheima.service.CouponService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/coupon/*")
public class CouponController extends HttpServlet {
    private final CouponService couponService = BeanFactory.getCouponService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String action = req.getPathInfo();
            if (action == null) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
                return;
            }
            switch (action) {
                case "/list":
                    handleList(req, resp);
                    break;
                case "/my":
                    handleMy(req, resp);
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
            }
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String action = req.getPathInfo();
            if (action == null) {
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
                return;
            }
            switch (action) {
                case "/grab":
                    handleGrab(req, resp);
                    break;
                default:
                    BaseServletUtil.writeError(resp, ErrorCode.NOT_FOUND, "未识别操作");
            }
        } catch (BusinessException e) {
            BaseServletUtil.writeError(resp, e.getCode(), e.getMessage());
        } catch (Exception e) {
            BaseServletUtil.writeError(resp, ErrorCode.SERVER_ERROR, "服务器异常，请重试");
        }
    }

    private void handleList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        BaseServletUtil.writeSuccess(resp, couponService.listAvailableCoupons());
    }

    private void handleMy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long userId = (Long) req.getAttribute("userId");
        BaseServletUtil.writeSuccess(resp, couponService.listMyCoupons(userId));
    }

    private void handleGrab(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Long userId = (Long) req.getAttribute("userId");
        GrabCouponRequest dto = RequestParser.parse(req, GrabCouponRequest.class);
        String couponCode = couponService.grabCoupon(dto.getCouponId(), userId);
        BaseServletUtil.writeSuccess(resp, couponCode);
    }
}