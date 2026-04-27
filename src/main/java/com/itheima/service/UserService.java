package com.itheima.service;

import com.itheima.dao.UserDao;
import com.itheima.exception.AuthException;
import com.itheima.exception.NotFoundException;
import com.itheima.response.LogInResponse;
import com.itheima.pojo.User;
import com.itheima.util.MyConnectionPool;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;

import java.sql.Connection;
import java.sql.SQLException;

public class UserService {
    private UserDao userDao=new UserDao();
    private TokenService tokenService=new TokenService();

    public LogInResponse login(long id,String rawPassword) throws Exception {
        User dbUser = userDao.getUserForLoginById(id);
        String tokenStr=doLogin(dbUser,rawPassword);
        return new LogInResponse(dbUser.getId(),dbUser.getUserName(),tokenStr);

    }
    public LogInResponse login(String phone, String rawPassword) throws Exception {
        User dbUser = userDao.getUserForLoginByPhone(phone);
        String tokenStr=doLogin(dbUser,rawPassword);
        return new LogInResponse(dbUser.getId(),dbUser.getUserName(),tokenStr);
    }


//执行登录，外部不调用
    private String doLogin(User user, String rawPassword) {

        if (user == null) {
            throw new NotFoundException("USER_NOT_FOUND");
        }
        if (!PasswordUtil.isPasswordCorrect(rawPassword, user.getHashedPassword())) {
            throw new AuthException("WRONG_PASSWORD");
        }
        user.clear();
        return tokenService.getNewToken(user.getId());
    }


    //    用户注册,返回Id
    public long registerAsUser(String userName,String password,String phone){
        Connection conn=null;
        long userId;
        try {
            conn=MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            User user=new User(userName,phone,password);
            if(!userDao.isPhoneUsed(conn,phone)){
                userId= userDao.addUser(conn, user.getUserName(), user.getHashedPassword(), user.getPhone());
            }
            else {
                throw new AuthException("PHONE_IN_USE");
            }
            conn.commit();//提交提交提交提交提交提交提交提交
            return userId;
        }catch (SQLException e){
            try{//rollback本身也可能报异常
                conn.rollback();
            }catch (SQLException ex){
                e.addSuppressed(ex);
            }
            throw new RuntimeException("REGISTER_FAILED",e);
        }
        finally {//连接池自己会开启自动提交
            MyConnectionPool.release(conn);
        }
    }
//




    public void changePassword(long userId, String phone, String oldPassword, String newPassword) throws SQLException {


    if (!PasswordUtil.isPasswordLegal(newPassword)) {
        throw new RuntimeException("PASSWORD_ILLEGAL");
    }

    Connection conn = null;
    try {
        conn = MyConnectionPool.getConnection();
        conn.setAutoCommit(false);
        doChangePassword(conn, userId, phone, oldPassword, newPassword);
        conn.commit();
    } catch (Exception e) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            }
        }
        throw e;
    } finally {
        MyConnectionPool.release(conn);
        }
}
    private void doChangePassword(Connection conn,  long userId,String phone, String oldPassword, String newPassword) throws SQLException {

        User dbUser = userDao.getUserForLoginById(conn, userId);

        if (dbUser == null) {
            throw new RuntimeException("USER_NOT_FOUND");
        }

        // 校验手机号是否匹配
        if (!phone.equals(dbUser.getPhone())) {
            throw new RuntimeException("PHONE_INCORRECT");
        }

        // 校验旧密码
        if (!PasswordUtil.isPasswordCorrect(oldPassword, dbUser.getHashedPassword())) {
            throw new RuntimeException("OLD_PASSWORD_ERROR");
        }

        // 更新密码
        String newHashedPassword = PasswordUtil.hashPassword(newPassword);
        int rows=userDao.updateUserPassword(conn,phone,userId, newHashedPassword);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }
    
    public void changeUserName(long userId,String newName) throws SQLException {
        if (newName == null || newName.length() >= 50) {
            throw new RuntimeException("NAME_ILLEGAL");
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            doChangeUserName(conn,userId,newName);
            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
            }
           throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    public void changePhone(long userId,String oldPhone,String newPhone) throws SQLException {


        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            doChangePhone(conn, userId, oldPhone, newPhone);
            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
            }
            throw e;
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void doChangeUserName(Connection conn, long userId, String newName) throws SQLException {
        if (userDao.isUserExist(conn,userId)) {
            throw new RuntimeException("USER_NOT_FOUND");
        }
        int rows = userDao.updateUserName(conn,userId, newName);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }

    private void doChangePhone(Connection conn, long userId, String oldPhone, String newPhone) throws SQLException {
        User dbUser = userDao.getUserForProfileById(conn, userId);
        if (dbUser == null) {
            throw new RuntimeException("USER_NOT_FOUND");
        }
        // 校验旧手机号是否匹配
        if (!oldPhone.equals(dbUser.getPhone())) {
            throw new RuntimeException("PHONE_INCORRECT");
        }
        // 校验新手机号格式
        if (!StringUtil.phoneCheck(newPhone)) {
            throw new RuntimeException("PHONE_ILLEGAL");
        }
        // 如果新手机号与旧手机号相同，不做任何修改
        if (newPhone.equals(oldPhone)) {
            throw new RuntimeException("PHONE_EQUAL");
        }
        // 检查新手机号是否被其他用户使用
        if (userDao.isPhoneUsed(conn, newPhone)) {
            throw new RuntimeException("PHONE_IN_USE");
        }
        int rows = userDao.updateUserPhone(conn, userId, newPhone);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }

    //根据id查询用户名


}
