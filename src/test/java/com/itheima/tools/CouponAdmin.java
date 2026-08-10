package com.itheima.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.TimeZone;

/**
 * 管理员工具：向数据库添加优惠券。独立运行，不依赖 Web 容器。
 */
public class CouponAdmin {
    private static final String URL = "jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "MySQL";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL驱动加载失败", e);
        }
    }

    public static void main(String[] args) {
        // 开始时间：当前时间，立即生效
//        LocalDateTime beginTime = LocalDateTime.now().withNano(0);
        // 结束时间：7天后
//        LocalDateTime endTime = beginTime.plusDays(7);

        LocalDateTime beginTime=LocalDateTime.of(
                2026,5,12,
                19,0,0
        );
        LocalDateTime endTime=LocalDateTime.of(
                2026,5,15,
                20,0,0
        );
        String title = "某当劳12.9板烧鸡腿堡券";
        int stock = 999;

        String sql = "INSERT INTO coupon (title, stock, begin_time, end_time) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setInt(2, stock);
            ps.setTimestamp(3, Timestamp.valueOf(beginTime));
            ps.setTimestamp(4, Timestamp.valueOf(endTime));
            ps.executeUpdate();
            System.out.println("优惠券添加成功");
            System.out.println("标题: " + title);
            System.out.println("库存: " + stock);
            System.out.println("开始时间: " + beginTime);
            System.out.println("结束时间: " + endTime);
        } catch (Exception e) {
            System.out.println("添加失败: " + e.getMessage());
        }
        System.out.println(TimeZone.getDefault());
    }
}
