package com.itheima.service;

import com.itheima.dao.CouponDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.factory.BeanFactory;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CouponService {
    private final CouponDao couponDao = BeanFactory.getCouponDao();

    public String grabCoupon(long couponId, long userId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            int rows = couponDao.deductStock(conn, couponId);
            if (rows == 0) {
                try { conn.rollback(); } catch (SQLException ex) { }
                throw new ConflictException("库存不足或活动未开始/已结束");
            }

            String couponCode = generateCouponCode();
            try {
                couponDao.insertOrder(conn, couponId, userId, couponCode);
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ex) { }
                if (e.getErrorCode() == 1062) {
                    throw new ConflictException("您已抢过该优惠券");
                }
                throw new ServerException("抢购失败");
            }

            conn.commit();
            return couponCode;
        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { }
            }
            throw new ServerException("抢购失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<Map<String, Object>> listAvailableCoupons() {
        try {
            return couponDao.findAvailableCoupons();
        } catch (SQLException e) {
            throw new ServerException("查询失败");
        }
    }

    public List<Map<String, Object>> listMyCoupons(long userId) {
        try {
            return couponDao.findOrdersByUserId(userId);
        } catch (SQLException e) {
            throw new ServerException("查询失败");
        }
    }

    private String generateCouponCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}