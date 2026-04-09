package com.itheima.dao;

import java.sql.*;

public class UserDao {
    private static final String url = "jdbc:mysql:///TVDatabase?useSSL=false";
    private static final String username = "root";
    private static final String password = "MySQL";

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
            System.out.println("注册在Dao出问题");
            throw new RuntimeException(e);
        }
    }

    public static String getPasswordHashByID(String id) {
        String sql = "select hashedPassword from userTable where id=?";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);

            // 获取结果集
            try (ResultSet res = pstmt.executeQuery()) {
                if (res.next()) {
                    return res.getString("hashedPassword");
                } else {
                    return null; // 用户不存在
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
                //暂时不处理异常
            }
        } catch (SQLException e) {//catch的是conn和pstmt的异常
            throw new RuntimeException(e);
            //暂时不处理异常
        }
    }


    //通过ID获取哈希后的密码

    public static String getPasswordHashByPhone(String phone) {
        String sql = "select hashedPassword from userTable where phone=?";
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
            return null;//返回null，上一层就会认为这个用户不存在
        }
    }

    public static boolean isPhoneUsed(String phone){
        String sql = "select hashedPassword from userTable where phone=?";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 设置参数（占位符从1开始）
            pstmt.setString(1, phone);

            // 获取结果集
            try (ResultSet res = pstmt.executeQuery()) {
                if (res.next()) {
                    return false;
                } else {
                    return true; // 用户不存在
                }
            }
        } catch (SQLException e) {
            return true;//返回null，上一层就会认为这个用户不存在
        }
    }

}
