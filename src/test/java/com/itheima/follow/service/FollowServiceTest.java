package com.itheima.follow.service;

import com.itheima.follow.dao.FollowDao;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowServiceTest {

    private FollowDao followDao;
    private UserDao userDao;
    private TransactionTemplate tt;
    private Connection conn;
    private FollowService service;

    @BeforeEach
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        userDao = mock(UserDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new FollowService(followDao, userDao, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private User user(long id, String name) {
        return new User(id, name, 0, 0);
    }

    // ===== follow =====

    @Test
    void followSelfThrowsConflict() {
        assertThrows(ConflictException.class, () -> service.follow(7L, 7L));
        verifyNoInteractions(followDao);
        verify(tt, never()).execute(any());
    }

    @Test
    void followAlreadyFollowedThrowsConflict() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.follow(7L, 8L));
        verify(followDao, never()).addFollow(any(), anyLong(), anyLong());
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
    }

    @Test
    void followSuccessUpdatesBothCounts() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);

        service.follow(7L, 8L);

        verify(followDao).addFollow(conn, 7L, 8L);
        verify(userDao).updateFollowCount(conn, 7L, 1);
        verify(userDao).updateFollowerCount(conn, 8L, 1);
    }

    @Test
    void followAddSqlErrorThrowsServerException() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);
        when(followDao.addFollow(conn, 7L, 8L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.follow(7L, 8L));
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(userDao, never()).updateFollowerCount(any(), anyLong(), anyInt());
    }

    // ===== unfollow =====

    @Test
    void unfollowSelfThrowsConflict() {
        assertThrows(ConflictException.class, () -> service.unfollow(7L, 7L));
        verifyNoInteractions(followDao);
        verify(tt, never()).execute(any());
    }

    @Test
    void unfollowNotFollowedThrowsConflict() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.unfollow(7L, 8L));
        verify(followDao, never()).deleteFollow(any(), anyLong(), anyLong());
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
    }

    @Test
    void unfollowSuccessUpdatesBothCounts() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);

        service.unfollow(7L, 8L);

        verify(followDao).deleteFollow(conn, 7L, 8L);
        verify(userDao).updateFollowCount(conn, 7L, -1);
        verify(userDao).updateFollowerCount(conn, 8L, -1);
    }

    @Test
    void unfollowDeleteSqlErrorThrowsServerException() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);
        when(followDao.deleteFollow(conn, 7L, 8L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.unfollow(7L, 8L));
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(userDao, never()).updateFollowerCount(any(), anyLong(), anyInt());
    }

    // ===== getFollowingList =====

    @Test
    void getFollowingListEmptyReturnsEmptyList() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, 7L)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertTrue(result.isEmpty());
        verify(userDao, never()).findUsersByIds(any(), anyList());
        verify(followDao, never()).getFollowedIds(any(), anyLong(), anyList());
    }

    @Test
    void getFollowingListWithCurrentUserFillsFollowedFlag() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, 7L)).thenReturn(List.of(8L, 9L));
        when(userDao.findUsersByIds(conn, List.of(8L, 9L)))
                .thenReturn(List.of(user(8L, "bob"), user(9L, "carol")));
        // 当前用户仅关注了 9
        when(followDao.getFollowedIds(conn, 7L, List.of(8L, 9L))).thenReturn(Set.of(9L));

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertEquals(2, result.size());
        assertEquals(8L, result.get(0).get("userId"));
        assertEquals("bob", result.get(0).get("username"));
        assertFalse((Boolean) result.get(0).get("isFollowed"));
        assertFalse((Boolean) result.get(0).get("isSelf"));
        assertEquals(9L, result.get(1).get("userId"));
        assertTrue((Boolean) result.get(1).get("isFollowed"));
        assertFalse((Boolean) result.get(1).get("isSelf"));
    }

    @Test
    void getFollowingListMarksSelfWhenIdMatchesCurrentUser() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, 7L)).thenReturn(List.of(7L));
        when(userDao.findUsersByIds(conn, List.of(7L))).thenReturn(List.of(user(7L, "alice")));
        when(followDao.getFollowedIds(conn, 7L, List.of(7L))).thenReturn(Collections.emptySet());

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertEquals(1, result.size());
        assertTrue((Boolean) result.get(0).get("isSelf"));
        assertFalse((Boolean) result.get(0).get("isFollowed"));
    }

    @Test
    void getFollowingListWithoutCurrentUserSkipsFollowQuery() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, 7L)).thenReturn(List.of(8L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));

        List<Map<String, Object>> result = service.getFollowingList(7L, null);

        assertEquals(1, result.size());
        assertFalse((Boolean) result.get(0).get("isFollowed"));
        assertFalse((Boolean) result.get(0).get("isSelf"));
        verify(followDao, never()).getFollowedIds(any(), anyLong(), anyList());
    }

    @Test
    void getFollowingListSqlErrorThrowsServerException() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, 7L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getFollowingList(7L, 7L));
    }

    // ===== getFollowerList =====

    @Test
    void getFollowerListEmptyReturnsEmptyList() throws SQLException {
        when(followDao.getFollowerUserIds(conn, 7L)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.getFollowerList(7L, 7L);

        assertTrue(result.isEmpty());
        verify(userDao, never()).findUsersByIds(any(), anyList());
    }

    @Test
    void getFollowerListNormalFillsStatus() throws SQLException {
        when(followDao.getFollowerUserIds(conn, 9L)).thenReturn(List.of(8L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));
        when(followDao.getFollowedIds(conn, 7L, List.of(8L))).thenReturn(Set.of(8L));

        List<Map<String, Object>> result = service.getFollowerList(9L, 7L);

        assertEquals(1, result.size());
        assertEquals(8L, result.get(0).get("userId"));
        assertEquals("bob", result.get(0).get("username"));
        assertTrue((Boolean) result.get(0).get("isFollowed"));
        assertFalse((Boolean) result.get(0).get("isSelf"));
    }
}