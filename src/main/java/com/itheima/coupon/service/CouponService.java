package com.itheima.coupon.service;

import com.itheima.coupon.dao.CouponDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class CouponService {

    private final CouponDao couponDao;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(CouponService.class);

    @InjectConstructor
    public CouponService(CouponDao couponDao, TransactionTemplate transactionTemplate) {
        this.couponDao = couponDao;
        this.transactionTemplate = transactionTemplate;
    }

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
                    // T11-B：包装点即源头——本行是该链唯一带堆栈记录（LOG_CONVENTION §3.1 附加纪律 2）
                    LOGGER.log(Level.SEVERE, "抢购失败, couponId=" + couponId + ", userId=" + userId, e);
                    throw new ServerException("抢购失败");
                }
                return couponCode;
            } catch (SQLException e) {
                // T11-B：包装点即源头——本行是该链唯一带堆栈记录（LOG_CONVENTION §3.1 附加纪律 2）
                LOGGER.log(Level.SEVERE, "抢购失败, couponId=" + couponId + ", userId=" + userId, e);
                throw new ServerException("抢购失败");
            }
        });
    }

    public List<Map<String, Object>> listAvailableCoupons() {
        return transactionTemplate.execute(conn -> {
            try {
                return couponDao.findAvailableCoupons(conn);
            } catch (SQLException e) {
                // T11-B：包装点即源头——本行是该链唯一带堆栈记录（LOG_CONVENTION §3.1 附加纪律 2）
                LOGGER.log(Level.SEVERE, "优惠券列表查询失败", e);
                throw new ServerException("查询失败");
            }
        });
    }

    public List<Map<String, Object>> listMyCoupons(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return couponDao.findOrdersByUserId(conn, userId);
            } catch (SQLException e) {
                // T11-B：包装点即源头——本行是该链唯一带堆栈记录（LOG_CONVENTION §3.1 附加纪律 2）
                LOGGER.log(Level.SEVERE, "我的优惠券查询失败, userId=" + userId, e);
                throw new ServerException("查询失败");
            }
        });
    }

    private String generateCouponCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
