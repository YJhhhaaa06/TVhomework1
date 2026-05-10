package com.itheima.dao;

import com.itheima.pojo.User;

import java.sql.*;
import java.util.List;

import com.itheima.util.MyConnectionPool;

public class UserDao {


    //增
    //添加用户
    public long addUser(Connection conn, String username, String hashedPassword, String phone) throws SQLException {
        String sql = "insert into users(username,hashed_password,phone) values(?,?,?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, username);
            pstmt.setString(2, hashedPassword);
            pstmt.setString(3, phone);
            // 执行插入，返回影响行数
            int rows = pstmt.executeUpdate();

            if (rows == 0) {
                throw new SQLException("插入失败");
            }
            //获取主键（用户ID）
            else {
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    return rs.getLong(1);//返回的是虚表，不通过字段名获取id
                } else {
                    //获取不到ID也当异常处理
                    throw new SQLException("插入成功，但未获取到ID");
                }
            }
        }
    }

    //删
    //删除用户
    public int deleteUser(Connection conn, String phone, long id) throws SQLException {
        String sql = "delete from users where phone=? and id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            pstmt.setLong(2, id);
            return pstmt.executeUpdate();
        }
    }

    //查
    //通过ID获取用户名
    public String findUsernameById(long id) throws SQLException {
        String sql = "select username from users where id=?";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
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
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public String findUsernameByPhone(String phone) throws SQLException {
        String sql = "select username from users where phone=?";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, phone);
                // 获取结果集
                try (ResultSet res = pstmt.executeQuery()) {
                    if (res.next()) {
                        return res.getString("username");
                    } else {
                        return null; // 用户不存在
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //为登录获取用户
    public User getUserForLoginById(Connection conn,long id) throws SQLException {
        String sql = "select * from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildUserForLogin(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    public User getUserForLoginById(long id) throws SQLException {
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return getUserForLoginById(conn,id);
        }catch (SQLException e){
            if (conn != null) {
                    try {
                        conn.close(); // 出异常直接关闭
                    } catch (SQLException ex) {
                        e.addSuppressed(ex);//将关闭失败的异常挂到e上
                    }
                    conn = null;
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    public User getUserForLoginByPhone(String phone) throws SQLException {
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return getUserForLoginByPhone(conn,phone);
        }catch (SQLException e){
            if (conn != null) {
                try {
                    conn.close(); // 出异常直接关闭
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
                conn = null;
            }
            throw e;
        }finally {
            MyConnectionPool.release(conn);
        }
    }
    public User getUserForLoginByPhone(Connection conn, String phone) throws SQLException {
        String sql = "select * from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // 设置参数（占位符从1开始）
            pstmt.setString(1, phone);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildUserForLogin(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }

    //为用户信息获取用户
    public User getUserForProfileById(Connection conn,long id) throws SQLException {
        String sql = "select * from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildUserForProfile(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    public User getUserForProfileById(long id) throws SQLException {
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return getUserForProfileById(conn,id);
        }catch (SQLException e){
            if (conn != null) {
                try {
                    conn.close(); // 出异常直接关闭
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
                conn = null;
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    public User getUserForProfileByPhone(Connection conn,String phone)throws SQLException{
        String sql = "select * from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildUserForProfile(rs);
                } else {
                    return null; // 用户不存在
                }
            }
        }
    }
    public User getUserForProfileByPhone(String phone)throws SQLException{

        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return getUserForProfileByPhone(conn,phone);
        }catch (SQLException e){
            if (conn != null) {
                try {
                    conn.close(); // 出异常直接关闭
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
                conn = null;
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }











    //查看手机号是否已经被使用
    public boolean isPhoneUsed(String phone) throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
           return isPhoneUsed(conn,phone);
        }catch (SQLException e){
            if(conn!=null){
                try {
                    conn.close();
                }catch (SQLException ex){
                    e.addSuppressed(ex);
                }
                conn=null;
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    public boolean isPhoneUsed(Connection conn, String phone) throws SQLException {
        String sql = "select 1 from users where phone=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();//手机号已被使用返回true
            }
        }
    }

    //查看用户名是否被使用
    public boolean isUsernameUsed(Connection conn,String username)throws SQLException{
        String sql = "select 1 from users where username=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();//用户名已被使用返回true
            }
        }
    }

    //id是否存在 (用户是否存在)
    public boolean isUserExist(long id) throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return isUserExist(conn,id);
        }catch (SQLException e){
            if(conn!=null){
                try {
                    conn.close();
                }catch (SQLException ex){
                    e.addSuppressed(ex);
                }
                conn=null;
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    public boolean isUserExist(Connection conn,long id) throws SQLException {
        String sql = "select 1 from users where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            // 获取结果集
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();//id存在返回true
            }
        }
    }

    //根据手机号查询id
    public long findIDbyPhone(String phone) throws SQLException {
        String sql = "select id from users where phone=?";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, phone);
                try (ResultSet res = pstmt.executeQuery()) {
                    if (res.next()) {
                        return res.getLong("id");
                    } else {
                        throw new RuntimeException("USER_NOT_FOUND"); // 用户不存在
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public String findPhoneById(long id) throws SQLException {
        String sql = "select phone from users where id=?";
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                try (ResultSet res = pstmt.executeQuery()) {
                    if (res.next()) {
                        return res.getString("phone");
                    } else {
                        return null; // 用户不存在
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //批量查询用户（仅id和username，给关注/粉丝列表用）
    public List<User> findUsersByIds(Connection conn, List<Long> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT id, username FROM users WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");
        List<User> result = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    User u = new User();
                    u.setId(rs.getLong("id"));
                    u.setUserName(rs.getString("username"));
                    result.add(u);
                }
            }
        }
        return result;
    }

    //改
    //更新关注数（delta 为正表示+1关注，为负表示-1关注）
    public int updateFollowCount(Connection conn, long userId, int delta) throws SQLException {
        String sql = "update users set follow_count = follow_count + ? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, delta);
            pstmt.setLong(2, userId);
            return pstmt.executeUpdate();
        }
    }

    //更新粉丝数
    public int updateFollowerCount(Connection conn, long userId, int delta) throws SQLException {
        String sql = "update users set follower_count = follower_count + ? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, delta);
            pstmt.setLong(2, userId);
            return pstmt.executeUpdate();
        }
    }

    //修改用户名
    public int updateUserName(Connection conn, long id, String newName) throws SQLException {
        String sql = "update users set username=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setLong(2, id);
            return pstmt.executeUpdate();
        }
    }

    //修改手机号
    public int updateUserPhone(Connection conn, long id, String newPhone) throws SQLException {
        String sql = "update users set phone=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPhone);
            pstmt.setLong(2, id);
            return pstmt.executeUpdate();
        }
    }

    //修改密码
    public int updateUserPassword(Connection conn, long id, String newHashedPassword) throws SQLException {
        String sql = "update users set hashed_password=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newHashedPassword);
            pstmt.setLong(2, id);
            return pstmt.executeUpdate();
        }
    }

}


