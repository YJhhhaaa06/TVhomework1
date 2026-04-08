package com.itheima.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class Register {
    public static int addUser(String user,String hashedPassword,String phone){
        String url = "jdbc:mysql:///TVDatabase?useSSL=false";
        String username = "root";
        String password = "MySQL";
        String sql="insert into userTable(username,hashedPassword,phone) values(?,?,?)";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 设置参数（占位符从1开始）
            pstmt.setString(1, user);
            pstmt.setString(2, hashedPassword);
            pstmt.setString(3, phone);


            // 执行插入，返回受影响的行数
            int result = pstmt.executeUpdate();
            return result;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }



}
