package com.itheima.coupon.service;

import com.itheima.coupon.dao.CouponDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CouponServiceTest {

    private CouponDao couponDao;
    private TransactionTemplate tt;
    private Connection conn;
    private CouponService service;

    @BeforeEach
    void setUp() throws Exception {
        couponDao = mock(CouponDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new CouponService(couponDao, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    // ===== grabCoupon =====

    @Test
    void grabCouponSuccessReturnsGeneratedCode() throws SQLException {
        when(couponDao.deductStock(conn, 3L)).thenReturn(1);

        String code = service.grabCoupon(3L, 7L);

        assertNotNull(code);
        assertEquals(16, code.length());
        assertTrue(code.matches("[0-9A-Z]{16}"));
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(couponDao).insertOrder(eq(conn), eq(3L), eq(7L), codeCaptor.capture());
        assertEquals(code, codeCaptor.getValue());
        verify(couponDao).deductStock(conn, 3L);
    }

    @Test
    void grabCouponNoStockThrowsConflict() throws SQLException {
        // 库存不足 / 活动未开始 / 已结束 三语义在 service 层不可区分（同一 SQL 条件），一用例覆盖
        when(couponDao.deductStock(conn, 3L)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class, () -> service.grabCoupon(3L, 7L));
        assertTrue(ex.getMessage().contains("库存不足"));
        verify(couponDao, never()).insertOrder(any(), anyLong(), anyLong(), anyString());
    }

    @Test
    void grabCouponDuplicateOrderThrowsConflict() throws SQLException {
        // 唯一索引冲突：MySQL error 1062 → 已抢过
        when(couponDao.deductStock(conn, 3L)).thenReturn(1);
        doThrow(new SQLException("duplicate key", "23000", 1062))
                .when(couponDao).insertOrder(eq(conn), eq(3L), eq(7L), anyString());

        ConflictException ex = assertThrows(ConflictException.class, () -> service.grabCoupon(3L, 7L));
        assertTrue(ex.getMessage().contains("已抢过"));
    }

    @Test
    void grabCouponInsertOrderSqlErrorThrowsServerException() throws SQLException {
        when(couponDao.deductStock(conn, 3L)).thenReturn(1);
        doThrow(new SQLException("db error", "08001", 9999))
                .when(couponDao).insertOrder(eq(conn), eq(3L), eq(7L), anyString());

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(CouponService.class));
        try {
            assertThrows(ServerException.class, () -> service.grabCoupon(3L, 7L));
        } finally {
            probe.detach();
        }

        // T11-B：包装点即源头——内层 catch（1062 之外）是"下单 SQL 失败"链唯一带堆栈记录
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "抢购失败, couponId=3, userId=7", SQLException.class);
    }

    @Test
    void grabCouponDeductStockSqlErrorThrowsServerException() throws SQLException {
        when(couponDao.deductStock(conn, 3L)).thenThrow(new SQLException("db down"));

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(CouponService.class));
        try {
            assertThrows(ServerException.class, () -> service.grabCoupon(3L, 7L));
        } finally {
            probe.detach();
        }

        // T11-B：包装点即源头——外层 catch 是"扣库存 SQL 失败"链唯一带堆栈记录
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "抢购失败, couponId=3, userId=7", SQLException.class);
        verify(couponDao, never()).insertOrder(any(), anyLong(), anyLong(), anyString());
    }

    // ===== listAvailableCoupons =====

    @Test
    void listAvailableCouponsReturnsDaoResult() throws SQLException {
        Map<String, Object> map = Map.of("id", 3L, "title", "新人券");
        when(couponDao.findAvailableCoupons(conn)).thenReturn(List.of(map));

        List<Map<String, Object>> result = service.listAvailableCoupons();

        assertEquals(1, result.size());
        assertSame(map, result.get(0));
        verify(couponDao).findAvailableCoupons(conn);
    }

    @Test
    void listAvailableCouponsEmptyList() throws SQLException {
        when(couponDao.findAvailableCoupons(conn)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.listAvailableCoupons();

        assertTrue(result.isEmpty());
    }

    @Test
    void listAvailableCouponsSqlErrorThrowsServerException() throws SQLException {
        when(couponDao.findAvailableCoupons(conn)).thenThrow(new SQLException("db down"));

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(CouponService.class));
        try {
            assertThrows(ServerException.class, () -> service.listAvailableCoupons());
        } finally {
            probe.detach();
        }

        // T11-B：包装点即源头
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "优惠券列表查询失败", SQLException.class);
    }

    // ===== listMyCoupons =====

    @Test
    void listMyCouponsReturnsDaoResult() throws SQLException {
        Map<String, Object> map = Map.of("id", 1L, "couponId", 3L, "title", "新人券");
        when(couponDao.findOrdersByUserId(conn, 7L)).thenReturn(List.of(map));

        List<Map<String, Object>> result = service.listMyCoupons(7L);

        assertEquals(1, result.size());
        assertSame(map, result.get(0));
        verify(couponDao).findOrdersByUserId(conn, 7L);
    }

    @Test
    void listMyCouponsEmptyList() throws SQLException {
        when(couponDao.findOrdersByUserId(conn, 7L)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.listMyCoupons(7L);

        assertTrue(result.isEmpty());
    }

    @Test
    void listMyCouponsSqlErrorThrowsServerException() throws SQLException {
        when(couponDao.findOrdersByUserId(conn, 7L)).thenThrow(new SQLException("db down"));

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(CouponService.class));
        try {
            assertThrows(ServerException.class, () -> service.listMyCoupons(7L));
        } finally {
            probe.detach();
        }

        // T11-B：包装点即源头
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "我的优惠券查询失败, userId=7", SQLException.class);
    }
}