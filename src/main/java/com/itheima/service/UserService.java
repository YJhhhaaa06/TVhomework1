package com.itheima.service;

import com.itheima.model.command.ChangePasswordCommand;
import com.itheima.model.command.LoginCommand;
import com.itheima.model.command.LoginType;
import com.itheima.model.command.RegisterCommand;
import com.itheima.dao.UserDao;
import com.itheima.exception.AuthException;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ConflictException;
import com.itheima.exception.DatabaseException;
import com.itheima.exception.DuplicatePhoneException;
import com.itheima.exception.InvalidPhoneException;
import com.itheima.exception.ParamException;
import com.itheima.exception.PasswordIncorrectException;
import com.itheima.exception.ServerException;
import com.itheima.exception.UserNotFoundException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.vo.LoginVO;
import com.itheima.model.entity.User;
import com.itheima.util.TransactionTemplate;
import com.itheima.util.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class UserService {

    @Inject
    private UserDao userDao;
    @Inject
    private TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(UserService.class);

    public LoginVO login(LoginCommand loginCommand){
        if(loginCommand.getType()== LoginType.BY_ID){
            return login(loginCommand.getId(),loginCommand.getPassword());
        }
        return login(loginCommand.getPhone(),loginCommand.getPassword());
    }

    public LoginVO login(long id, String rawPassword) {
        User dbUser = transactionTemplate.execute(conn -> {
            try {
                return userDao.getUserForLoginById(conn, id);
            } catch (SQLException e) {
                throw new DatabaseException("登录失败", e);
            }
        });
        String tokenStr = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUserName(), tokenStr);
    }
    public LoginVO login(String phone, String rawPassword) {
        User dbUser = transactionTemplate.execute(conn -> {
            try {
                return userDao.getUserForLoginByPhone(conn, phone);
            } catch (SQLException e) {
                throw new DatabaseException("登录失败", e);
            }
        });
        String tokenStr = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUserName(), tokenStr);
    }


//执行登录，外部不调用
    private String doLogin(User user, String rawPassword) {

        if (user == null) {
            throw new UserNotFoundException();
        }
        if (!PasswordUtil.isPasswordCorrect(rawPassword, user.getHashedPassword())) {
            throw new PasswordIncorrectException();
        }
        return JwtUtil.generateToken(user.getId());
    }


    //    用户注册,返回Id
    public long registerAsUser(RegisterCommand rc){
        String username=rc.getUsername();
        String phone=rc.getPhone();
        String password=rc.getPassword();
        String hashedPassword=PasswordUtil.hashPassword(password);
        return transactionTemplate.execute(conn -> {
            if(userDao.isPhoneUsed(conn,phone)){
                throw new AuthException("电话号码已被使用");
            }
            if(userDao.isUsernameUsed(conn,username)) {
                throw new ConflictException("用户名已被占用");
            }
            try {
                return userDao.addUser(conn, username, hashedPassword, phone);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "用户注册失败, phone=" + StringUtil.maskPhone(phone), e);
                throw new ServerException("服务器异常");
            }
        });
    }
//




    public void changePassword(long userId, ChangePasswordCommand command) {
        String phone = command.getPhone();
        String oldPassword = command.getOldPassword();
        String newPassword = command.getNewPassword();

        transactionTemplate.execute(conn -> {
            try {
                doChangePassword(conn, userId, phone, oldPassword, newPassword);
                return null;
            } catch (BusinessException e) {
                LOGGER.log(Level.SEVERE, "修改密码失败, userId=" + userId, e);
                throw e;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "修改密码失败, userId=" + userId, e);
                throw new DatabaseException("修改密码失败", e);
            }
        });
    }
    private void doChangePassword(Connection conn,  long userId,String phone, String oldPassword, String newPassword) throws SQLException {

        User dbUser = userDao.getUserForLoginById(conn, userId);

        if (dbUser == null) {
            throw new UserNotFoundException();
        }

        // 校验手机号是否匹配
        if (!phone.equals(dbUser.getPhone())) {
            throw new ParamException("手机号不匹配");
        }

        // 校验旧密码
        if (!PasswordUtil.isPasswordCorrect(oldPassword, dbUser.getHashedPassword())) {
            throw new PasswordIncorrectException("旧密码错误");
        }

        // 更新密码
        String newHashedPassword = PasswordUtil.hashPassword(newPassword);
        int rows = userDao.updateUserPassword(conn, userId, newHashedPassword);
        if (rows == 0) {
            throw new DatabaseException("更新失败");
        }
    }
    
    public void changeUserName(long userId,String newName) {
        if (newName == null || newName.length() >= 50) {
            throw new ParamException("用户名不合法");
        }

        transactionTemplate.execute(conn -> {
            try {
                doChangeUserName(conn, userId, newName);
                return null;
            } catch (BusinessException e) {
                LOGGER.log(Level.SEVERE, "修改用户名失败, userId=" + userId, e);
                throw e;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "修改用户名失败, userId=" + userId, e);
                throw new DatabaseException("修改用户名失败", e);
            }
        });
    }
    public void changePhone(long userId,String oldPhone,String newPhone) {
        transactionTemplate.execute(conn -> {
            try {
                doChangePhone(conn, userId, oldPhone, newPhone);
                return null;
            } catch (BusinessException e) {
                LOGGER.log(Level.SEVERE, "修改手机号失败, userId=" + userId, e);
                throw e;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "修改手机号失败, userId=" + userId, e);
                throw new DatabaseException("修改手机号失败", e);
            }
        });
    }

    private void doChangeUserName(Connection conn, long userId, String newName) throws SQLException {
        if (!userDao.isUserExist(conn, userId)) {
            throw new UserNotFoundException();
        }
        int rows = userDao.updateUserName(conn, userId, newName);
        if (rows == 0) {
            throw new DatabaseException("更新失败");
        }
    }

    private void doChangePhone(Connection conn, long userId, String oldPhone, String newPhone) throws SQLException {
        User dbUser = userDao.getUserForProfileById(conn, userId);
        if (dbUser == null) {
            throw new UserNotFoundException();
        }
        // 校验旧手机号是否匹配
        if (!oldPhone.equals(dbUser.getPhone())) {
            throw new ParamException("手机号不匹配");
        }
        // 校验新手机号格式
        if (!StringUtil.phoneCheck(newPhone)) {
            throw new InvalidPhoneException();
        }
        // 如果新手机号与旧手机号相同，不做任何修改
        if (newPhone.equals(oldPhone)) {
            throw new ParamException("新手机号与旧手机号相同");
        }
        // 检查新手机号是否被其他用户使用
        if (userDao.isPhoneUsed(conn, newPhone)) {
            throw new DuplicatePhoneException();
        }
        int rows = userDao.updateUserPhone(conn, userId, newPhone);
        if (rows == 0) {
            throw new DatabaseException("更新失败");
        }
    }

    //根据id查询用户名


    //查询用户是否为管理员（0=普通用户，1=管理员）
    public boolean isAdmin(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return userDao.getUserRole(conn, userId) == 1;
            } catch (SQLException e) {
                throw new DatabaseException("查询用户角色失败", e);
            }
        });
    }

}
