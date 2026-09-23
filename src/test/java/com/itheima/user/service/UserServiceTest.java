package com.itheima.user.service;

import com.itheima.user.dao.UserDao;
import com.itheima.content.service.ContentCache;
import com.itheima.exception.ConflictException;
import com.itheima.exception.DatabaseException;
import com.itheima.exception.DuplicatePhoneException;
import com.itheima.exception.InvalidPhoneException;
import com.itheima.exception.ParamException;
import com.itheima.exception.PasswordIncorrectException;
import com.itheima.exception.UserNotFoundException;
import com.itheima.user.model.command.ChangePasswordCommand;
import com.itheima.user.model.command.LoginCommand;
import com.itheima.user.model.command.RegisterCommand;
import com.itheima.user.model.entity.User;
import com.itheima.user.model.vo.LoginVO;
import com.itheima.util.JwtUtil;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.PasswordUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserDao userDao;
    private ContentCache contentCache;
    private TransactionTemplate tt;
    private Connection conn;
    private UserService service;

    @BeforeEach
    void setUp() throws Exception {
        userDao = mock(UserDao.class);
        contentCache = mock(ContentCache.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new UserService(userDao, tt, contentCache);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private User loginUser(String rawPassword) {
        return new User(7L, PasswordUtil.hashPassword(rawPassword), "alice", "13800000001");
    }

    /** 注册成功路径的公共打桩：手机号/用户名均未占用，插入返回 id=42（T13）。 */
    private void stubRegisterSuccess() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(false);
        when(userDao.isUsernameUsed(conn, "bob")).thenReturn(false);
        when(userDao.addUser(eq(conn), eq("bob"), anyString(), eq("13800000001"))).thenReturn(42L);
    }

    @Test
    void loginByPhoneSuccessReturnsLoginVO() throws SQLException {
        User dbUser = loginUser("abc123");
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(dbUser);

        LoginVO vo = service.login("13800000001", "abc123");

        assertEquals(7L, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertEquals(7L, JwtUtil.getUserId(vo.getToken()));
    }

    @Test
    void loginByIdSuccessReturnsLoginVO() throws SQLException {
        User dbUser = loginUser("abc123");
        when(userDao.getUserForLoginById(conn, 7L)).thenReturn(dbUser);

        LoginVO vo = service.login(7L, "abc123");

        assertEquals(7L, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertNotNull(vo.getToken());
    }

    @Test
    void loginCommandByPhoneDispatchesToPhoneLogin() throws SQLException {
        User dbUser = loginUser("abc123");
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(dbUser);

        LoginVO vo = service.login(LoginCommand.byPhone("13800000001", "abc123"));

        assertEquals(7L, vo.getId());
    }

    @Test
    void loginUserNotFoundThrows() throws SQLException {
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> service.login("13800000001", "abc123"));
    }

    @Test
    void loginWrongPasswordThrows() throws SQLException {
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(loginUser("abc123"));

        assertThrows(PasswordIncorrectException.class, () -> service.login("13800000001", "wrong"));
    }

    @Test
    void loginSqlErrorWrapsDatabaseException() throws SQLException {
        when(userDao.getUserForLoginByPhone(conn, "13800000001"))
                .thenThrow(new SQLException("db down"));

        assertThrows(DatabaseException.class, () -> service.login("13800000001", "abc123"));
    }

    // ------------------------------------------------------------------
    // T9（log2-09）：成功路径里程碑 INFO——登录
    // ------------------------------------------------------------------

    @Test
    void loginSuccessWritesExactlyOneMilestoneInfo() throws SQLException {
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(loginUser("abc123"));

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(UserService.class));
        LoginVO vo;
        try {
            vo = service.login("13800000001", "abc123");
        } finally {
            probe.detach();
        }

        assertNotNull(vo.getToken());
        assertEquals(List.of("登录成功, userId=7"), probe.messagesAtLevel(Level.INFO),
                "登录成功路径应恰有一条里程碑 INFO（请求级访问行由 AccessLogFilter 承担，此处只记业务里程碑）");
        assertEquals(Level.INFO, probe.records().get(0).getLevel(), "里程碑用 INFO");
        assertNull(probe.records().get(0).getThrown(), "成功路径不带堆栈");
        assertFalse(probe.records().get(0).getMessage().contains("13800000001"),
                "账号（手机号）不落盘——只记 userId");
    }

    @Test
    void loginFailureWritesNoMilestoneInfo() throws SQLException {
        when(userDao.getUserForLoginByPhone(conn, "13800000001")).thenReturn(loginUser("abc123"));

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(UserService.class));
        try {
            assertThrows(PasswordIncorrectException.class, () -> service.login("13800000001", "wrong"));
        } finally {
            probe.detach();
        }

        assertTrue(probe.atLevel(Level.INFO).isEmpty(),
                "失败路径不记 INFO（可预期业务拒绝由 ExceptionFilter 的 WARNING 结论行承载）");
    }

    @Test
    void registerDuplicatePhoneThrowsDuplicatePhoneException() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(true);

        assertThrows(DuplicatePhoneException.class, () ->
                service.registerAsUser(RegisterCommand.getInstance("13800000001", "abc123", "bob")));
    }

    @Test
    void registerDuplicateUsernameThrowsConflictException() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(false);
        when(userDao.isUsernameUsed(conn, "bob")).thenReturn(true);

        assertThrows(ConflictException.class, () ->
                service.registerAsUser(RegisterCommand.getInstance("13800000001", "abc123", "bob")));
    }

    @Test
    void registerSuccessHashesPasswordAndReturnsId() throws SQLException {
        stubRegisterSuccess();

        long id = service.registerAsUser(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertEquals(42L, id);
        ArgumentCaptor<String> hashedCaptor = ArgumentCaptor.forClass(String.class);
        verify(userDao).addUser(eq(conn), eq("bob"), hashedCaptor.capture(), eq("13800000001"));
        assertNotEquals("abc123", hashedCaptor.getValue());
        assertTrue(PasswordUtil.isPasswordCorrect("abc123", hashedCaptor.getValue()));
    }

    // ------------------------------------------------------------------
    // T9（log2-09）：成功路径里程碑 INFO——注册
    // ------------------------------------------------------------------

    @Test
    void registerSuccessWritesExactlyOneMilestoneInfo() throws SQLException {
        stubRegisterSuccess();

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(UserService.class));
        long id;
        try {
            id = service.registerAsUser(RegisterCommand.getInstance("13800000001", "abc123", "bob"));
        } finally {
            probe.detach();
        }

        assertEquals(42L, id);
        assertEquals(List.of("用户注册成功, userId=42"), probe.messagesAtLevel(Level.INFO),
                "注册提交成功 = 账号创建里程碑；**不记手机号/用户名**（只记 userId）");
    }

    @Test
    void registerFailureWritesNoMilestoneInfo() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(true);

        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(UserService.class));
        try {
            assertThrows(DuplicatePhoneException.class, () -> service.registerAsUser(
                    RegisterCommand.getInstance("13800000001", "abc123", "bob")));
        } finally {
            probe.detach();
        }

        assertTrue(probe.atLevel(Level.INFO).isEmpty(), "注册失败不得留下成功里程碑");
    }

    // ------------------------------------------------------------------
    // T13：注册后自动登录兜底（池 U-16）——注册成功即成功，自动登录失败
    // 返回 token=null 的 LoginVO（调用方提示"请手动登录"），不抛异常
    // ------------------------------------------------------------------

    @Test
    void registerAndLoginSuccessReturnsTokenAndRegisteredIdentity() throws SQLException {
        stubRegisterSuccess();
        when(userDao.getUserForLoginById(conn, 42L))
                .thenReturn(new User(42L, PasswordUtil.hashPassword("abc123"), "bob", "13800000001"));

        LoginVO vo = service.registerAndLogin(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertEquals(42L, vo.getId());
        assertEquals("bob", vo.getUsername());
        assertNotNull(vo.getToken());
        assertFalse(vo.getToken().isBlank());
    }

    @Test
    void registerAndLoginAutoLoginUserMissingFallsBackToManualLogin() throws SQLException {
        stubRegisterSuccess();
        when(userDao.getUserForLoginById(conn, 42L)).thenReturn(null);

        LoginVO vo = service.registerAndLogin(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertNull(vo.getToken());
        assertEquals(42L, vo.getId());
        assertEquals("bob", vo.getUsername());
        // 注册已提交：兜底不改写"用户已落库"这一事实
        verify(userDao).addUser(eq(conn), eq("bob"), anyString(), eq("13800000001"));
    }

    @Test
    void registerAndLoginAutoLoginWrongPasswordFallsBackToManualLogin() throws SQLException {
        stubRegisterSuccess();
        when(userDao.getUserForLoginById(conn, 42L))
                .thenReturn(new User(42L, PasswordUtil.hashPassword("other"), "bob", "13800000001"));

        LoginVO vo = service.registerAndLogin(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertNull(vo.getToken());
        assertEquals(42L, vo.getId());
    }

    @Test
    void registerAndLoginAutoLoginSqlErrorFallsBackToManualLogin() throws SQLException {
        stubRegisterSuccess();
        when(userDao.getUserForLoginById(conn, 42L)).thenThrow(new SQLException("db down"));

        LoginVO vo = service.registerAndLogin(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertNull(vo.getToken());
        assertEquals(42L, vo.getId());
    }

    @Test
    void registerAndLoginRegisterFailureStillThrows() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(true);

        assertThrows(DuplicatePhoneException.class, () -> service.registerAndLogin(
                RegisterCommand.getInstance("13800000001", "abc123", "bob")));
        // 注册本身失败 → 不进入自动登录（兜底只覆盖登录失败，不吞注册失败）
        verify(userDao, never()).getUserForLoginById(any(Connection.class), anyLong());
    }

    @Test
    void changePasswordWrongOldPasswordThrows() throws SQLException {
        when(userDao.getUserForLoginById(conn, 7L)).thenReturn(loginUser("abc123"));

        PasswordIncorrectException ex = assertThrows(PasswordIncorrectException.class, () ->
                service.changePassword(7L, new ChangePasswordCommand("13800000001", "old", "new1")));
        assertTrue(ex.getMessage().contains("旧密码错误"));
    }

    @Test
    void changePasswordPhoneMismatchThrows() throws SQLException {
        when(userDao.getUserForLoginById(conn, 7L)).thenReturn(loginUser("abc123"));

        assertThrows(ParamException.class, () ->
                service.changePassword(7L, new ChangePasswordCommand("13900000002", "abc123", "new1")));
    }

    @Test
    void changePasswordSuccessUpdatesHashedPassword() throws SQLException {
        when(userDao.getUserForLoginById(conn, 7L)).thenReturn(loginUser("abc123"));
        when(userDao.updateUserPassword(eq(conn), eq(7L), anyString())).thenReturn(1);

        service.changePassword(7L, new ChangePasswordCommand("13800000001", "abc123", "newpass1"));

        ArgumentCaptor<String> hashedCaptor = ArgumentCaptor.forClass(String.class);
        verify(userDao).updateUserPassword(eq(conn), eq(7L), hashedCaptor.capture());
        assertTrue(PasswordUtil.isPasswordCorrect("newpass1", hashedCaptor.getValue()));
    }

    @Test
    void changePasswordZeroRowsThrowsDatabaseException() throws SQLException {
        when(userDao.getUserForLoginById(conn, 7L)).thenReturn(loginUser("abc123"));
        when(userDao.updateUserPassword(eq(conn), eq(7L), anyString())).thenReturn(0);

        assertThrows(DatabaseException.class, () ->
                service.changePassword(7L, new ChangePasswordCommand("13800000001", "abc123", "new1")));
    }

    @Test
    void changeUserNameInvalidRejectedBeforeDao() {
        assertThrows(ParamException.class, () -> service.changeUserName(7L, null));
        assertThrows(ParamException.class, () -> service.changeUserName(7L, ""));
        assertThrows(ParamException.class, () -> service.changeUserName(7L, "   "));
        assertThrows(ParamException.class, () -> service.changeUserName(7L, "a".repeat(50)));
        verifyNoInteractions(userDao);
    }

    @Test
    void changeUserNameUserMissingThrows() throws SQLException {
        when(userDao.isUserExist(conn, 7L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> service.changeUserName(7L, "newName"));
    }

    @Test
    void changeUserNameSuccess() throws SQLException {
        when(userDao.isUserExist(conn, 7L)).thenReturn(true);
        when(userDao.isUsernameUsed(conn, "newName")).thenReturn(false);
        when(userDao.updateUserName(conn, 7L, "newName")).thenReturn(1);

        service.changeUserName(7L, "newName");

        verify(userDao).updateUserName(conn, 7L, "newName");
        verify(contentCache).invalidateAuthorContentKeys(7L);
    }

    @Test
    void changeUserNameDuplicateNameThrows() throws SQLException {
        when(userDao.isUserExist(conn, 7L)).thenReturn(true);
        when(userDao.isUsernameUsed(conn, "newName")).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.changeUserName(7L, "newName"));

        verify(userDao, never()).updateUserName(any(Connection.class), anyLong(), anyString());
        verify(contentCache, never()).invalidateAuthorContentKeys(anyLong());
    }

    @Test
    void changePhoneInvalidNewFormatThrows() throws SQLException {
        User dbUser = new User(7L, "hash", "alice", "13800000001");
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(dbUser);

        assertThrows(InvalidPhoneException.class, () ->
                service.changePhone(7L, "13800000001", "123"));
    }

    @Test
    void changePhoneSameAsOldThrows() throws SQLException {
        User dbUser = new User(7L, "hash", "alice", "13800000001");
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(dbUser);

        assertThrows(ParamException.class, () ->
                service.changePhone(7L, "13800000001", "13800000001"));
    }

    @Test
    void changePhoneDuplicateNewPhoneThrows() throws SQLException {
        User dbUser = new User(7L, "hash", "alice", "13800000001");
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(dbUser);
        when(userDao.isPhoneUsed(conn, "13900000002")).thenReturn(true);

        assertThrows(DuplicatePhoneException.class, () ->
                service.changePhone(7L, "13800000001", "13900000002"));
    }

    @Test
    void changePhoneSuccess() throws SQLException {
        User dbUser = new User(7L, "hash", "alice", "13800000001");
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(dbUser);
        when(userDao.isPhoneUsed(conn, "13900000002")).thenReturn(false);
        when(userDao.updateUserPhone(conn, 7L, "13900000002")).thenReturn(1);

        // T8：该点**无 HTTP 入口**（e2e 覆盖不到）→ 在此断言"成功恰留一条审计记录"，补齐验收①的 7/7。
        // 探针挂真实 audit logger、用完摘除；记录同时会经真实 FileHandler 落 JUnit 链路日志目录。
        List<LogRecord> auditRecords = new ArrayList<>();
        Handler probe = new Handler() {
            @Override
            public void publish(LogRecord record) {
                auditRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger auditLogger = LogUtil.getAuditLogger();
        auditLogger.addHandler(probe);
        try {
            service.changePhone(7L, "13800000001", "13900000002");
        } finally {
            auditLogger.removeHandler(probe);
        }

        verify(userDao).updateUserPhone(conn, 7L, "13900000002");
        assertEquals(1, auditRecords.size(), "changePhone 成功路径应恰有一条审计记录");
        assertEquals("action=user.changePhone operatorId=7 target=userId:7 result=success",
                auditRecords.get(0).getMessage(), "审计行口径见 AuditLog 类注释 / LOG_CONVENTION 3.5");
    }

    @Test
    void isAdminTrueForRoleOne() throws SQLException {
        when(userDao.getUserRole(conn, 7L)).thenReturn(1);
        assertTrue(service.isAdmin(7L));
    }

    @Test
    void isAdminFalseForRoleZero() throws SQLException {
        when(userDao.getUserRole(conn, 7L)).thenReturn(0);
        assertFalse(service.isAdmin(7L));
    }

    @Test
    void isAdminSqlErrorWrapsDatabaseException() throws SQLException {
        when(userDao.getUserRole(conn, 7L)).thenThrow(new SQLException("db down"));
        assertThrows(DatabaseException.class, () -> service.isAdmin(7L));
    }
}
