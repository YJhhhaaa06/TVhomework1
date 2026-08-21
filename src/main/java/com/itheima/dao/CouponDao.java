package com.itheima.dao;

import com.itheima.ioc.annotation.Component;

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

    public Map<String, Object> findById(Connection conn, long couponId) throws SQLException {
        String sql = "SELECT id, title, stock, begin_time, end_time, create_time FROM coupon WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, couponId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToMap(rs);
                }
            }
        }
        return null;
    }

    public List<Map<String, Object>> findAvailableCoupons(Connection conn) throws SQLException {
        String sql = "SELECT id, title, stock, begin_time, end_time, create_time FROM coupon WHERE end_time > NOW() ORDER BY begin_time ASC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rowToMap(rs));
            }
        }
        return list;
    }

    public List<Map<String, Object>> findOrdersByUserId(Connection conn, long userId) throws SQLException {
        String sql = "SELECT co.id, co.coupon_id, co.coupon_code, co.status, co.create_time, c.title " +
                     "FROM coupon_order co JOIN coupon c ON co.coupon_id = c.id " +
                     "WHERE co.user_id = ? ORDER BY co.create_time DESC";
        List<Map<String, Object>> list = new ArrayList<>();
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
