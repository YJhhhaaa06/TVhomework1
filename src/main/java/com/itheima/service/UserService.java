package com.itheima.service;

import com.itheima.model.command.ChangePasswordCommand;
import com.itheima.model.command.LoginCommand;
import com.itheima.model.command.LoginType;
import com.itheima.model.command.RegisterCommand;
import com.itheima.dao.UserDao;
import com.itheima.exception.AuthException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.LoginVO;
import com.itheima.model.entity.User;
import com.itheima.util.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class UserService {

    @Inject
    private UserDao userDao;
    private static final Logger LOGGER =
            LogUtil.getLogger(UserService.class);

    public LoginVO login(LoginCommand loginCommand)throws Exception{
        if(loginCommand.getType()== LoginType.BY_ID){
            return login(loginCommand.getId(),loginCommand.getPassword());
        }
        return login(loginCommand.getPhone(),loginCommand.getPassword());
    }

    public LoginVO login(long id, String rawPassword) throws Exception {
        User dbUser = userDao.getUserForLoginById(id);
        String tokenStr=doLogin(dbUser,rawPassword);
        return new LoginVO(dbUser.getId(),dbUser.getUserName(),tokenStr);

    }
    public LoginVO login(String phone, String rawPassword) throws Exception {
        User dbUser = userDao.getUserForLoginByPhone(phone);
        String tokenStr=doLogin(dbUser,rawPassword);
        return new LoginVO(dbUser.getId(),dbUser.getUserName(),tokenStr);
    }


//执行登录，外部不调用
    private String doLogin(User user, String rawPassword) {

        if (user == null) {
            throw new NotFoundException("USER_NOT_FOUND");
        }
        if (!PasswordUtil.isPasswordCorrect(rawPassword, user.getHashedPassword())) {
            throw new AuthException("WRONG_PASSWORD");
        }
        return JwtUtil.generateToken(user.getId());
    }


    //    用户注册,返回Id
    public long registerAsUser(RegisterCommand rc){
        String username=rc.getUsername();
        String phone=rc.getPhone();
        String password=rc.getPassword();
        String hashedPassword=PasswordUtil.hashPassword(password);
        Connection conn=null;
        long userId;
        try {
            conn=MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if(userDao.isPhoneUsed(conn,phone)){
                throw new AuthException("电话号码已被使用");
            }
            if(userDao.isUsernameUsed(conn,username)) {
                throw new AuthException("用户名已被占用");
            }
            userId=userDao.addUser(conn,username,hashedPassword,phone);
            conn.commit();//提交提交提交提交提交提交提交提交
            return userId;
        }catch (SQLException e){
            LOGGER.log(Level.SEVERE, "用户注册失败, phone=" + phone, e);
            try{//rollback本身也可能报异常
                conn.rollback();
            }catch (SQLException ex){
                e.addSuppressed(ex);
            }
            throw new ServerException("服务器异常");
        }
        finally {//连接池自己会开启自动提交
            MyConnectionPool.release(conn);
        }
    }
//




    public void changePassword(long userId, ChangePasswordCommand command) throws SQLException {
        String phone = command.getPhone();
        String oldPassword = command.getOldPassword();
        String newPassword = command.getNewPassword();

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            doChangePassword(conn, userId, phone, oldPassword, newPassword);
            conn.commit();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "修改密码失败, userId=" + userId, e);
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
        int rows = userDao.updateUserPassword(conn, userId, newHashedPassword);
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
            LOGGER.log(Level.SEVERE, "修改用户名失败, userId=" + userId, e);
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
            LOGGER.log(Level.SEVERE, "修改手机号失败, userId=" + userId, e);
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
        if (!userDao.isUserExist(conn, userId)) {
            throw new NotFoundException("USER_NOT_FOUND");
        }
        int rows = userDao.updateUserName(conn, userId, newName);
        if (rows == 0) {
            throw new ServerException("UPDATE_FAILED");
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
