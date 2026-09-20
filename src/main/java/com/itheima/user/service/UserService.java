package com.itheima.user.service;

import com.itheima.user.model.command.ChangePasswordCommand;
import com.itheima.user.model.command.LoginCommand;
import com.itheima.user.model.command.LoginType;
import com.itheima.user.model.command.RegisterCommand;
import com.itheima.user.dao.UserDao;
import com.itheima.content.service.ContentCache;
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
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.model.vo.LoginVO;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;
import com.itheima.util.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class UserService {

    private final UserDao userDao;
    private final TransactionTemplate transactionTemplate;
    private final ContentCache contentCache;
    private static final Logger LOGGER =
            LogUtil.getLogger(UserService.class);

    @InjectConstructor
    public UserService(UserDao userDao, TransactionTemplate transactionTemplate, ContentCache contentCache) {
        this.userDao = userDao;
        this.transactionTemplate = transactionTemplate;
        this.contentCache = contentCache;
    }

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


    /**
     * 注册并自动登录（池 U-16 兜底）：**注册成功即视为成功**。
     *
     * <p>自动登录失败（用户查不到 / 密码不匹配 / 登录期 DB 异常——理论上不应发生）时不再向上抛异常
     * （旧行为：前端收到错误响应，用户误以为注册失败，且无 token、无提示），而是返回
     * {@code token == null} 的 {@link LoginVO}（id/username 仍为刚注册的用户），由调用方提示"注册成功，请手动登录"。
     *
     * <p>不重试、不补登：注册与自动登录仍是两个独立事务（注册事务先提交，再做登录查询），
     * 语句集与改造前一致；**注册本身失败**（手机号/用户名占用、插入异常）仍照旧抛错，不被兜底吞掉。
     */
    public LoginVO registerAndLogin(RegisterCommand rc) {
        long id = registerAsUser(rc);
        try {
            return login(id, rc.getPassword());
        } catch (BusinessException e) {
            // 注册已提交：不能把自动登录失败报成注册失败；留痕不静默
            LOGGER.log(Level.WARNING, "注册后自动登录失败, userId=" + id + ", 改为提示手动登录", e);
            return new LoginVO(id, rc.getUsername(), null);
        }
    }

    //    用户注册,返回Id
    public long registerAsUser(RegisterCommand rc){
        String username=rc.getUsername();
        String phone=rc.getPhone();
        String password=rc.getPassword();
        String hashedPassword=PasswordUtil.hashPassword(password);
        return transactionTemplate.execute(conn -> {
            if(userDao.isPhoneUsed(conn,phone)){
                throw new DuplicatePhoneException();
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
        if (newName == null || newName.isBlank() || newName.length() >= 50) {
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
        // DB 提交后级联失效该作者内容缓存 key；失败不抛（缓存仅作加速器，TTL 自愈）
        contentCache.invalidateAuthorContentKeys(userId);
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
        // 唯一性预校验（对齐全册注册先例，避免撞 DB UNIQUE 约束变 500）
        if (userDao.isUsernameUsed(conn, newName)) {
            throw new ConflictException("用户名已被占用");
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
