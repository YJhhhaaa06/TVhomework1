package com.itheima.dao;

import com.itheima.pojo.User;

import java.sql.*;

public class UserDao {


    //增
    //添加用户
    public static long addUser(Connection conn,String user,String hashedPassword,String phone) throws SQLException {
        String sql="insert into users(username,hashedPassword,phone) values(?,?,?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, user);
            pstmt.setString(2, hashedPassword);
            pstmt.setString(3, phone);
            // 执行插入，返回影响行数
            int rows = pstmt.executeUpdate();

            if(rows == 0){
                throw new SQLException("插入失败");
            }
            //获取主键（用户ID）
            else {ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    return rs.getLong("id");
                }
                else {
                    //获取不到ID也当异常处理
                    throw new SQLException("插入成功，但未获取到ID");
                }
            }
        }
    }

    //删
    //删除用户
    public static int deleteUser(Connection conn,String phone,long id) throws SQLException {
        String sql = "delete from users where phone=? and id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            pstmt.setLong(2, id);
            return pstmt.executeUpdate();
        }
    }

    //查
    //通过ID获取用户名
    public static String findUsernameById(Connection conn,long id)throws SQLException{
        String sql = "select username from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            // 获取结果集
            try (ResultSet res = pstmt.executeQuery()) {
                if (res.next()) {
                    return res.getString("username");
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    //通过ID获取用户
    public static User findUserByID(Connection conn,long id) throws SQLException {
        String sql = "select * from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.mapResultToUser(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    //根据手机号获取用户
    public static User findUserByPhone(Connection conn,String phone) throws SQLException {
        String sql = "select * from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // 设置参数（占位符从1开始）
            pstmt.setString(1, phone);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery();){
                if (rs.next()) {
                    return ResultMap.mapResultToUser(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    //查看手机号是否已经被使用
    public static boolean isPhoneUsed(Connection conn,String phone) throws SQLException {
        String sql = "select hashedPassword from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();//手机号已被使用返回true
            }
        }
    }
    //根据手机号查询id
    public static long findIDbyPhone(Connection conn,String phone)throws SQLException{
        String sql = "select id from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            try (ResultSet res = pstmt.executeQuery();){
                if (res.next()) {
                    return res.getLong("id");
                } else {
                    throw new RuntimeException("USER_NOT_FOUND"); // 用户不存在
                }
            }
        }
    }
    public static String findPhoneById(Connection conn,long id)throws SQLException{
        String sql = "select phone from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            try (ResultSet res = pstmt.executeQuery();){
                if (res.next()) {
                    return res.getString("id");
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }

    //改
    //修改用户名
    public static int updateUserName(Connection conn,String phone,long id,String newName) throws SQLException {
        String sql = "update users set username=? where phone=? and id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setString(2, phone);
            pstmt.setLong(3, id);
            return pstmt.executeUpdate();
        }
    }
    //修改手机号
    public static int updateUserPhone(Connection conn,String phone,long id,String newPhone) throws SQLException {
        String sql = "update users set phone=? where phone=? and id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPhone);
            pstmt.setString(2, phone);
            pstmt.setLong(3, id);
            return pstmt.executeUpdate();
        }
    }
    //修改密码
    public static int updateUserPassword(Connection conn,String phone,long id,String newHashedPassword) throws SQLException {
        String sql = "update users set hashedPassword=? where phone=? and id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newHashedPassword);
            pstmt.setString(2, phone);
            pstmt.setLong(3, id);
            return pstmt.executeUpdate();
        }
    }
    
    


}
