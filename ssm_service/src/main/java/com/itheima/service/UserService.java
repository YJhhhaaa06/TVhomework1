package com.itheima.service;

import com.itheima.dao.UserDao;
import com.itheima.pojo.LogInResult;
import com.itheima.pojo.Token;
import com.itheima.pojo.User;
import com.itheima.util.MyConnectionPool;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;
import com.itheima.util.TokenUtil;

import java.sql.Connection;
import java.sql.SQLException;

public class UserService {


    private static LogInResult doLogin(User user, String rawPassword) {
        String tokenStr=null;
        if (user == null) {
            throw new RuntimeException("USER_NOT_FOUND");
        }

        if (!PasswordUtil.isPasswordCorrect(rawPassword, user.getHashedPassword())) {
            throw new RuntimeException("PASSWORD_ERROR");
        }

        user.clear();


        Token token = TokenService.getNewToken(user.getId());
        try {
            tokenStr = TokenUtil.tokenToString(token);
        }catch (Exception e){
            throw new RuntimeException("TOKEN_ERROR",e);
        }


        return new LogInResult(user, tokenStr);
    }

    //作为通过id登录
//    public static LogInResult logInAsUserByID(long id, String rawPassword) throws Exception {
//        Connection conn=null;
//        try{
//            conn=MyConnectionPool.getConnection();
//            User user=UserDao.findUserByID(conn,id);
//
//            if (user==null){
//                throw new RuntimeException("USER_NOT_FOUND");
//            }
//            else{
//                if(PasswordService.checkPassword(rawPassword,user.getPassword())){
//                    user.clearHashedPassword();
//                    //返回字符串token
//                    Token token= TokenService.getNewToken(id);
//                    String tokenStr=TokenUtil.tokenToString(token);
//
//                    return new LogInResult(user,tokenStr);
//                    }
//                else {
//                    throw new RuntimeException("PASSWORD_ERROR");
//                    }
//            }
//        }finally {
//            MyConnectionPool.release(conn);
//        }
//    }

    public static LogInResult logInAsUserByID(long id, String rawPassword) throws Exception {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            User user = UserDao.findUserByID(conn, id);

            return doLogin(user, rawPassword);

        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //    通过手机号登录
//    public static LogInResult logInAsUserByPhone(String phone, String rawPassword) throws Exception {
//        Connection conn=null;
//        try{
//            conn=MyConnectionPool.getConnection();
//            User user=UserDao.findUserByPhone(conn,phone);
//
//            if (user==null){
//                throw new RuntimeException("USER_NOT_FOUND");
//            }
//            else{
//                if(PasswordService.checkPassword(rawPassword,user.getPassword())){
//                    user.clearHashedPassword();
//                    //返回字符串token
//                    Token token= TokenService.getNewToken(user.getId());
//                    String tokenStr=TokenUtil.tokenToString(token);
//
//                    return new LogInResult(user,tokenStr);
//                }
//                else {
//                    throw new RuntimeException("PASSWORD_ERROR");
//                }
//            }
//        }finally {
//            MyConnectionPool.release(conn);
//        }
//    }

    public static LogInResult logInAsUserByPhone(String phone, String rawPassword) throws Exception {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            User user = UserDao.findUserByPhone(conn, phone);
            return doLogin(user, rawPassword);

        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //    用户注册
    public static void registerAsUser(String userName,String password,String phone){
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if (registerCheck(conn,userName, password, phone)) {
                UserDao.addUser(conn, userName, PasswordUtil.hashPassword(password), phone);
            } else {
                throw new RuntimeException("INPUT_ERROR");
            }
            conn.commit();//提交提交提交提交提交提交提交提交
        }catch (Exception e){
            try{//rollback本身也可能报异常
                conn.rollback();
            }catch (SQLException ex){
                ex.printStackTrace();
            }
            throw new RuntimeException("REGISTER_FAILED",e);
        }
        finally {
            if(conn!=null){//之前开启了事务，还连接时要把自动提交打开
                try{
                conn.setAutoCommit(true);
                }catch (SQLException e){
                    e.printStackTrace();
                }
            }
            MyConnectionPool.release(conn);
        }
    }
//
//
//
//没问题返回true
    public static boolean registerCheck(Connection conn, String userName,String password,String phone) throws SQLException {
        if(!PasswordUtil.isPasswordLegal(password)){//密码太长
            return false;
        }
        if(!StringUtil.phoneCheck(phone)){//电话号码不对
            return false;
        }
        if ((userName.length()>=50)){//名字太长
            return false;
        }
        if(!UserDao.isPhoneUsed(conn,phone)){//电话号码被用了
            return false;
        }
        return true;
    }

//    public static void changePassword(User user,String phone,String oldPassword,String newPassword){
//        if(!PasswordUtil.isPasswordLegal(newPassword)){
//            throw new RuntimeException("PASSWORD_ILLEGAL");
//        }
//        Connection conn=null;
//        if(user==null){
//            throw new RuntimeException("NO_USER");
//        }
//        try{
//            conn=MyConnectionPool.getConnection();
//            conn.setAutoCommit(false);
//            long id= user.getId();
//            User targetUser=UserDao.findUserByID(conn,id);
//            if(targetUser==null){
//                throw new RuntimeException("USER_NOT_FOUND");
//            }
//            User targetUser2=UserDao.findUserByPhone(conn,phone);
//            if(targetUser2==null){
//                throw new RuntimeException("PHONE_INCORRECT");
//            }
//            if(id!=targetUser2.getId()){
//                throw new RuntimeException("PHONE_INCORRECT");
//            }
//            String hashedPassword=targetUser.getHashedPassword();
//            if(PasswordUtil.isPasswordCorrect(oldPassword,hashedPassword)){//如果密码正确
//                String newHashedPassword=PasswordUtil.hashPassword(newPassword);
//                UserDao.updateUserPassword(conn,phone,id,newHashedPassword);
//            }
//            conn.commit();
//        }catch (Exception e){
//            try {
//                conn.rollback();
//            }catch (SQLException ex){
//                ex.printStackTrace();
//            }
//        }
//        finally {
//            try {
//                if(conn==null){
//                    throw new RuntimeException("FAIL_TO_CONNECT_TO_DATABASE");
//                }
//                conn.setAutoCommit(true);
//            } catch (SQLException e) {
//                e.printStackTrace();
//            }
//        }
//    }
    public static void changePassword(User user, String phone, String oldPassword, String newPassword) {
    if (user == null) {
        throw new RuntimeException("NO_USER");
    }

    if (!PasswordUtil.isPasswordLegal(newPassword)) {
        throw new RuntimeException("PASSWORD_ILLEGAL");
    }

    Connection conn = null;

    try {
        conn = MyConnectionPool.getConnection();
        conn.setAutoCommit(false);

        doChangePassword(conn, user, phone, oldPassword, newPassword);

        conn.commit();

    } catch (Exception e) {

        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
        throw new RuntimeException("CHANGE_PASSWORD_FAILED", e);
    } finally {
        if (conn != null) {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        MyConnectionPool.release(conn);
    }
}
    private static void doChangePassword(Connection conn, User user, String phone, String oldPassword, String newPassword) throws SQLException {

        User dbUser = UserDao.findUserByID(conn, user.getId());

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
        int rows=UserDao.updateUserPassword(conn,phone,user.getId(), newHashedPassword);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }
    
    public static void changeUserName(User user,String newName){
        if (user == null) {
            throw new RuntimeException("NO_USER");
        }

        if (newName == null || newName.length() >= 50) {
            throw new RuntimeException("NAME_ILLEGAL");
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            doChangeUserName(conn, user, newName);

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            throw new RuntimeException("CHANGE_USERNAME_FAILED", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            MyConnectionPool.release(conn);
        }
    }
    public static void changePhone(User user,String oldPhone,String newPhone){
        if (user == null) {
            throw new RuntimeException("NO_USER");
        }

        if (oldPhone == null || newPhone == null) {
            throw new RuntimeException("INPUT_ERROR");
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            doChangePhone(conn, user, oldPhone, newPhone);

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            throw new RuntimeException("CHANGE_PHONE_FAILED", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            MyConnectionPool.release(conn);
        }
    }

    private static void doChangeUserName(Connection conn, User user, String newName) throws SQLException {
        User dbUser = UserDao.findUserByID(conn, user.getId());
        if (dbUser == null) {
            throw new RuntimeException("USER_NOT_FOUND");
        }

        // Use the current phone from DB to ensure we update the correct row
        int rows = UserDao.updateUserName(conn, dbUser.getPhone(), user.getId(), newName);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }

    private static void doChangePhone(Connection conn, User user, String oldPhone, String newPhone) throws SQLException {
        User dbUser = UserDao.findUserByID(conn, user.getId());
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
            return;
        }

        // 检查新手机号是否被其他用户使用
        if (UserDao.isPhoneUsed(conn, newPhone)) {
            throw new RuntimeException("PHONE_IN_USE");
        }

        int rows = UserDao.updateUserPhone(conn, oldPhone, user.getId(), newPhone);
        if (rows == 0) {
            throw new RuntimeException("UPDATE_FAILED");
        }
    }


}
