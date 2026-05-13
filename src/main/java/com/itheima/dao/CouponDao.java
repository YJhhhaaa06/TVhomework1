package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CouponDao {

    //建表语句
    //CREATE TABLE coupon (
    //    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '优惠券ID',
    //
    //    title VARCHAR(100) NOT NULL COMMENT '优惠券标题',
    //
    //    stock INT NOT NULL DEFAULT 0 COMMENT '库存',
    //
    //    begin_time DATETIME NOT NULL COMMENT '抢购开始时间',
    //
    //    end_time DATETIME NOT NULL COMMENT '抢购结束时间',
    //
    //    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    //
    //    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    //        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    //
    //    CHECK (stock >= 0)
    //) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    //CREATE TABLE coupon_order (
    //    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    //
    //    coupon_id BIGINT NOT NULL COMMENT '优惠券ID',
    //
    //    user_id BIGINT NOT NULL COMMENT '用户ID',
    //
    //    coupon_code VARCHAR(64) NOT NULL COMMENT '优惠券码',
    //
    //    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1成功 2已使用',
    //
    //    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抢购时间',
    //
    //    UNIQUE KEY uk_coupon_user (coupon_id, user_id),
    //
    //    UNIQUE KEY uk_coupon_code (coupon_code),
    //
    //    INDEX idx_user_id (user_id),
    //
    //    CONSTRAINT fk_coupon_order_coupon
    //        FOREIGN KEY (coupon_id)
    //        REFERENCES coupon(id)
    //        ON DELETE CASCADE
    //) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    public int deductStock(Connection conn, long couponId) throws SQLException {
        String sql = "UPDATE coupon SET stock = stock - 1 WHERE id = ? AND stock > 0 AND begin_time <= NOW() AND end_time >= NOW()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, couponId);
            return ps.executeUpdate();
        }
    }

    public void insertOrder(Connection conn, long couponId, long userId, String couponCode) throws SQLException {
        String sql = "INSERT INTO coupon_order (coupon_id, user_id, coupon_code) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, couponId);
            ps.setLong(2, userId);
            ps.setString(3, couponCode);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> findById(long couponId) throws SQLException {
        String sql = "SELECT id, title, stock, begin_time, end_time, create_time FROM coupon WHERE id = ?";
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, couponId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rowToMap(rs);
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
        return null;
    }

    public List<Map<String, Object>> findAvailableCoupons() throws SQLException {
        String sql = "SELECT id, title, stock, begin_time, end_time, create_time FROM coupon WHERE end_time > NOW() ORDER BY begin_time ASC";
        List<Map<String, Object>> list = new ArrayList<>();
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rowToMap(rs));
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
        return list;
    }

    public List<Map<String, Object>> findOrdersByUserId(long userId) throws SQLException {
        String sql = "SELECT co.id, co.coupon_id, co.coupon_code, co.status, co.create_time, c.title " +
                     "FROM coupon_order co JOIN coupon c ON co.coupon_id = c.id " +
                     "WHERE co.user_id = ? ORDER BY co.create_time DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", rs.getLong("id"));
                        map.put("couponId", rs.getLong("coupon_id"));
                        map.put("couponCode", rs.getString("coupon_code"));
                        map.put("status", rs.getInt("status"));
                        map.put("title", rs.getString("title"));
                        map.put("createTime", rs.getTimestamp("create_time").toLocalDateTime().toString());
                        list.add(map);
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
        return list;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rs.getLong("id"));
        map.put("title", rs.getString("title"));
        map.put("stock", rs.getInt("stock"));
        map.put("beginTime", rs.getTimestamp("begin_time").toLocalDateTime().toString());
        map.put("endTime", rs.getTimestamp("end_time").toLocalDateTime().toString());
        map.put("createTime", rs.getTimestamp("create_time").toLocalDateTime().toString());
        return map;
    }
}
