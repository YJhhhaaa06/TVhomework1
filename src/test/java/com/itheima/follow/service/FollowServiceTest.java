package com.itheima.follow.service;

import com.itheima.cache.ZSetCache;
import com.itheima.follow.dao.FollowDao;
import com.itheima.follow.model.dto.FollowPageResult;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowServiceTest {

    private FollowDao followDao;
    private UserDao userDao;
    private FollowCache followCache;
    private TransactionTemplate tt;
    private Connection conn;
    private FollowService service;

    @BeforeEach
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        userDao = mock(UserDao.class);
        followCache = mock(FollowCache.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new FollowService(followDao, userDao, followCache, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private User user(long id, String name) {
        return new User(id, name, 0, 0);
    }

    // ===== follow（业务校验 DB 直读，缓存写在 DB 提交后） =====

    @Test
    void followSelfThrowsConflict() {
        assertThrows(ConflictException.class, () -> service.follow(7L, 7L));
        verifyNoInteractions(followDao);
        verifyNoInteractions(followCache);
        verify(tt, never()).execute(any());
    }

    @Test
    void followAlreadyFollowedThrowsConflict() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.follow(7L, 8L));
        verify(followDao, never()).addFollow(any(), anyLong(), anyLong());
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(followCache, never()).cacheFollow(anyLong(), anyLong());
    }

    @Test
    void followSuccessUpdatesDbAndCacheAfterCommit() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);

        service.follow(7L, 8L);

        verify(followDao).addFollow(conn, 7L, 8L);
        verify(userDao).updateFollowCount(conn, 7L, 1);
        verify(userDao).updateFollowerCount(conn, 8L, 1);
        verify(followCache).cacheFollow(7L, 8L);
    }

    @Test
    void followAddSqlErrorThrowsServerExceptionAndSkipsCache() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);
        when(followDao.addFollow(conn, 7L, 8L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.follow(7L, 8L));
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(userDao, never()).updateFollowerCount(any(), anyLong(), anyInt());
        verify(followCache, never()).cacheFollow(anyLong(), anyLong());
    }

    // ===== unfollow =====

    @Test
    void unfollowSelfThrowsConflict() {
        assertThrows(ConflictException.class, () -> service.unfollow(7L, 7L));
        verifyNoInteractions(followDao);
        verifyNoInteractions(followCache);
        verify(tt, never()).execute(any());
    }

    @Test
    void unfollowNotFollowedThrowsConflict() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.unfollow(7L, 8L));
        verify(followDao, never()).deleteFollow(any(), anyLong(), anyLong());
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(followCache, never()).cacheUnfollow(anyLong(), anyLong());
    }

    @Test
    void unfollowSuccessUpdatesDbAndCacheAfterCommit() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);

        service.unfollow(7L, 8L);

        verify(followDao).deleteFollow(conn, 7L, 8L);
        verify(userDao).updateFollowCount(conn, 7L, -1);
        verify(userDao).updateFollowerCount(conn, 8L, -1);
        verify(followCache).cacheUnfollow(7L, 8L);
    }

    @Test
    void unfollowDeleteSqlErrorThrowsServerExceptionAndSkipsCache() throws SQLException {
        when(followDao.isFollowing(conn, 7L, 8L)).thenReturn(true);
        when(followDao.deleteFollow(conn, 7L, 8L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.unfollow(7L, 8L));
        verify(userDao, never()).updateFollowCount(any(), anyLong(), anyInt());
        verify(userDao, never()).updateFollowerCount(any(), anyLong(), anyInt());
        verify(followCache, never()).cacheUnfollow(anyLong(), anyLong());
    }

    // ===== getFollowingList（列表走关注缓存） =====

    @Test
    void getFollowingListEmptyReturnsEmptyList() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertTrue(result.isEmpty());
        verify(userDao, never()).findUsersByIds(any(), anyList());
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }

    @Test
    void getFollowingListWithCurrentUserFillsFollowedFlag() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(8L, 9L));
        when(userDao.findUsersByIds(conn, List.of(8L, 9L)))
                .thenReturn(List.of(user(8L, "bob"), user(9L, "carol")));
        // 当前用户仅关注了 9
        when(followCache.batchIsFollowing(7L, List.of(8L, 9L))).thenReturn(Map.of(9L, true));

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
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(userDao.findUsersByIds(conn, List.of(7L))).thenReturn(List.of(user(7L, "alice")));
        when(followCache.batchIsFollowing(7L, List.of(7L))).thenReturn(Collections.emptyMap());

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertEquals(1, result.size());
        assertTrue((Boolean) result.get(0).get("isSelf"));
        assertFalse((Boolean) result.get(0).get("isFollowed"));
    }

    @Test
    void getFollowingListWithoutCurrentUserSkipsFollowQuery() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(8L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));

        List<Map<String, Object>> result = service.getFollowingList(7L, null);

        assertEquals(1, result.size());
        assertFalse((Boolean) result.get(0).get("isFollowed"));
        assertFalse((Boolean) result.get(0).get("isSelf"));
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }

    @Test
    void getFollowingListUserQuerySqlErrorThrowsServerException() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(8L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getFollowingList(7L, 7L));
    }

    // ===== getFollowerList（列表走关注缓存） =====

    @Test
    void getFollowerListEmptyReturnsEmptyList() throws SQLException {
        when(followCache.getFollowerIds(9L)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.getFollowerList(9L, 7L);

        assertTrue(result.isEmpty());
        verify(userDao, never()).findUsersByIds(any(), anyList());
    }

    @Test
    void getFollowerListNormalFillsStatus() throws SQLException {
        when(followCache.getFollowerIds(9L)).thenReturn(List.of(8L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));
        when(followCache.batchIsFollowing(7L, List.of(8L))).thenReturn(Map.of(8L, true));

        List<Map<String, Object>> result = service.getFollowerList(9L, 7L);

        assertEquals(1, result.size());
        assertEquals(8L, result.get(0).get("userId"));
        assertEquals("bob", result.get(0).get("username"));
        assertTrue((Boolean) result.get(0).get("isFollowed"));
        assertFalse((Boolean) result.get(0).get("isSelf"));
    }

    // ===== 分页读（T7：A1 有序窗口 + B2 分页信封） =====

    private List<Long> idsOf(List<Map<String, Object>> list) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> item : list) {
            ids.add((Long) item.get("userId"));
        }
        return ids;
    }

    @Test
    void getFollowingListPagedAssemblesPageFromWindow() throws SQLException {
        // page=2 / pageSize=2 → offset=2：缓存窗口只回该页 ids 与总数，信封字段按 B2 口径组装
        when(followCache.getFollowingWindow(7L, 2L, 2))
                .thenReturn(new ZSetCache.Window(List.of(9L, 10L), 5L));
        when(userDao.findUsersByIds(conn, List.of(9L, 10L)))
                .thenReturn(List.of(user(9L, "carol"), user(10L, "dave")));
        when(followCache.batchIsFollowing(7L, List.of(9L, 10L))).thenReturn(Map.of(10L, true));

        FollowPageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 2, 2);

        assertEquals(List.of(9L, 10L), idsOf(page.getList()));
        assertEquals(5, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(2, page.getPageSize());
        assertEquals(3, page.getTotalPages());
        assertFalse((Boolean) page.getList().get(0).get("isFollowed"));
        assertTrue((Boolean) page.getList().get(1).get("isFollowed"));
    }

    @Test
    void getFollowingListPagedLoadsAndJudgesOnlyThatPage() throws SQLException {
        // 关键性能断言：DB 装载与批量判重只吃该页 ids（别"切了返回却仍全量判重"）
        when(followCache.getFollowingWindow(7L, 0L, 2))
                .thenReturn(new ZSetCache.Window(List.of(8L, 9L), 100L));
        when(userDao.findUsersByIds(conn, List.of(8L, 9L)))
                .thenReturn(List.of(user(8L, "bob"), user(9L, "carol")));
        when(followCache.batchIsFollowing(7L, List.of(8L, 9L))).thenReturn(Collections.emptyMap());

        service.getFollowingList(7L, 7L, 1, 2);

        verify(userDao, times(1)).findUsersByIds(eq(conn), eq(List.of(8L, 9L)));
        verify(followCache, times(1)).batchIsFollowing(7L, List.of(8L, 9L));
    }

    @Test
    void getFollowingListPagedOffsetBeyondTotalSkipsDb() throws SQLException {
        when(followCache.getFollowingWindow(7L, 20L, 10))
                .thenReturn(new ZSetCache.Window(Collections.emptyList(), 5L));

        FollowPageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 3, 10);

        assertTrue(page.getList().isEmpty());
        assertEquals(5, page.getTotal()); // 越界页仍回总数（前端据此判末页）
        assertEquals(1, page.getTotalPages());
        verify(userDao, never()).findUsersByIds(any(), anyList());
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }

    @Test
    void getFollowerListPagedAssemblesPageFromWindow() throws SQLException {
        when(followCache.getFollowerWindow(9L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));
        when(followCache.batchIsFollowing(7L, List.of(8L))).thenReturn(Map.of(8L, true));

        FollowPageResult<Map<String, Object>> page = service.getFollowerList(9L, 7L, 1, 10);

        assertEquals(List.of(8L), idsOf(page.getList()));
        assertEquals(1, page.getTotal());
        assertEquals(1, page.getTotalPages());
        assertTrue((Boolean) page.getList().get(0).get("isFollowed"));
    }

    @Test
    void getFollowingListDefaultPathDoesNotUseWindowRead() throws SQLException {
        // 缺省兼容回归：不传分页参数走全量路径（getFollowingIds），不触达窗口读
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(8L, 9L));
        when(userDao.findUsersByIds(conn, List.of(8L, 9L)))
                .thenReturn(List.of(user(8L, "bob"), user(9L, "carol")));
        when(followCache.batchIsFollowing(7L, List.of(8L, 9L))).thenReturn(Collections.emptyMap());

        List<Map<String, Object>> result = service.getFollowingList(7L, 7L);

        assertEquals(2, result.size());
        verify(followCache, never()).getFollowingWindow(anyLong(), anyLong(), anyInt());
    }

    @Test
    void getFollowingListPagedSqlErrorThrowsServerException() throws SQLException {
        when(followCache.getFollowingWindow(7L, 0L, 2))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getFollowingList(7L, 7L, 1, 2));
    }
}
