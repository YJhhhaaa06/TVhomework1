package com.itheima.service;

import com.itheima.dao.CouponDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CouponService {

    @Inject
    private CouponDao couponDao;
    @Inject
    private TransactionTemplate transactionTemplate;

    public String grabCoupon(long couponId, long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                int rows = couponDao.deductStock(conn, couponId);
                if (rows == 0) {
                    throw new ConflictException("库存不足或活动未开始/已结束");
                }

                String couponCode = generateCouponCode();
                try {
                    couponDao.insertOrder(conn, couponId, userId, couponCode);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) {
                        throw new ConflictException("您已抢过该优惠券");
                    }
                    throw new ServerException("抢购失败");
                }
                return couponCode;
            } catch (SQLException e) {
                throw new ServerException("抢购失败");
            }
        });
    }

    public List<Map<String, Object>> listAvailableCoupons() {
        return transactionTemplate.execute(conn -> {
            try {
                return couponDao.findAvailableCoupons(conn);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    public List<Map<String, Object>> listMyCoupons(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return couponDao.findOrdersByUserId(conn, userId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    private String generateCouponCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
