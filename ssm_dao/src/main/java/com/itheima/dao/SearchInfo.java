package com.itheima.dao;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;



import java.sql.*;

public class SearchInfo {
    public static String getPasswordHashByID(String id) {
        String url = "jdbc:mysql:///TVDatabase?useSSL=false";
        String username = "root";
        String password = "MySQL";
        String sql="select hashedPassword from userTable where id=?";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 设置参数（占位符从1开始）
            pstmt.setString(1, id);

            // 获取结果集
            try (ResultSet res = pstmt.executeQuery()) {
                if (res.next()) {
                    return res.getString("hashedPassword");
                } else {
                    return null; // 用户不存在
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    public static String getPasswordHashByPhone(String phone) {
        String url = "jdbc:mysql:///TVDatabase?useSSL=false";
        String username = "root";
        String password = "MySQL";
        String sql="select hashedPassword from userTable where phone=?";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 设置参数（占位符从1开始）
            pstmt.setString(1, phone);

            // 获取结果集
            try (ResultSet res = pstmt.executeQuery()) {
                if (res.next()) {
                    return res.getString("hashedPassword");
                } else {
                    return null; // 用户不存在
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }



}
