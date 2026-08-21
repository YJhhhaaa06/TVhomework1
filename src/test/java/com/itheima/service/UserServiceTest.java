package com.itheima.service;

import com.itheima.dao.UserDao;
import com.itheima.exception.AuthException;
import com.itheima.exception.ConflictException;
import com.itheima.exception.DatabaseException;
import com.itheima.exception.DuplicatePhoneException;
import com.itheima.exception.InvalidPhoneException;
import com.itheima.exception.ParamException;
import com.itheima.exception.PasswordIncorrectException;
import com.itheima.exception.UserNotFoundException;
import com.itheima.model.command.ChangePasswordCommand;
import com.itheima.model.command.LoginCommand;
import com.itheima.model.command.RegisterCommand;
import com.itheima.model.entity.User;
import com.itheima.model.vo.LoginVO;
import com.itheima.util.JwtUtil;
import com.itheima.util.PasswordUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserDao userDao;
    private TransactionTemplate tt;
    private Connection conn;
    private UserService service;

    @BeforeEach
    void setUp() throws Exception {
        userDao = mock(UserDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new UserService(userDao, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private User loginUser(String rawPassword) {
        return new User(7L, PasswordUtil.hashPassword(rawPassword), "alice", "13800000001");
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

    @Test
    void registerDuplicatePhoneThrowsAuthException() throws SQLException {
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(true);

        assertThrows(AuthException.class, () ->
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
        when(userDao.isPhoneUsed(conn, "13800000001")).thenReturn(false);
        when(userDao.isUsernameUsed(conn, "bob")).thenReturn(false);
        when(userDao.addUser(eq(conn), eq("bob"), anyString(), eq("13800000001"))).thenReturn(42L);

        long id = service.registerAsUser(
                RegisterCommand.getInstance("13800000001", "abc123", "bob"));

        assertEquals(42L, id);
        ArgumentCaptor<String> hashedCaptor = ArgumentCaptor.forClass(String.class);
        verify(userDao).addUser(eq(conn), eq("bob"), hashedCaptor.capture(), eq("13800000001"));
        assertNotEquals("abc123", hashedCaptor.getValue());
        assertTrue(PasswordUtil.isPasswordCorrect("abc123", hashedCaptor.getValue()));
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
        when(userDao.updateUserName(conn, 7L, "newName")).thenReturn(1);

        service.changeUserName(7L, "newName");

        verify(userDao).updateUserName(conn, 7L, "newName");
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

        service.changePhone(7L, "13800000001", "13900000002");

        verify(userDao).updateUserPhone(conn, 7L, "13900000002");
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
