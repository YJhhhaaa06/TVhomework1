package com.itheima.follow.service;

import com.itheima.cache.ZSetCache;
import com.itheima.follow.dao.FollowDao;
import com.itheima.common.model.dto.PageResult;
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
    /** 事务回调执行中标志（T12：断言缓存读发生在事务回调之外）。 */
    private boolean[] inTransaction;

    @BeforeEach
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        userDao = mock(UserDao.class);
        followCache = mock(FollowCache.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        inTransaction = new boolean[1];
        service = new FollowService(followDao, userDao, followCache, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            inTransaction[0] = true;
            try {
                return action.execute(conn);
            } finally {
                inTransaction[0] = false;
            }
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

    // ===== 缺省读路径（T11-A：两个缺省重载已删除） =====
    // 原 `getFollowingList(long,Long)` / `getFollowerList(long,Long)` 与对应 8 条用例已随 T11-A 删除
    // （缺省由 Controller 归一为 page 1 / pageSize 200，Service 只剩分页入口）。
    // 这 8 条里**仍有语义价值的断言已迁入分页路径**（见下方 getFollowingListPagedMarksSelfWhenIdMatchesCurrentUser /
    // getFollowingListPagedWithoutCurrentUserSkipsFollowQuery / getFollowerListPagedEmptyPageSkipsDbAndFollowQuery），
    // 覆盖面未削弱——「缺省返回数组」这一被废除的契约不再有对应断言。

    // ===== 分页读（T7：A1 有序窗口 + B2 分页信封；T11-A：唯一读入口） =====

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

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 2, 2);

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

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 3, 10);

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

        PageResult<Map<String, Object>> page = service.getFollowerList(9L, 7L, 1, 10);

        assertEquals(List.of(8L), idsOf(page.getList()));
        assertEquals(1, page.getTotal());
        assertEquals(1, page.getTotalPages());
        // T11-A 评审补强：粉丝路径的 username / isSelf 也需有显式断言（此前只在已删的缺省用例里覆盖）
        assertEquals("bob", page.getList().get(0).get("username"));
        assertTrue((Boolean) page.getList().get(0).get("isFollowed"));
        assertFalse((Boolean) page.getList().get(0).get("isSelf"));
    }

    @Test
    void getFollowingListPagedMarksSelfWhenIdMatchesCurrentUser() throws SQLException {
        // 由原缺省路径用例迁移（T11-A）：isSelf 判定在分页路径下语义不变
        when(followCache.getFollowingWindow(7L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(7L), 1L));
        when(userDao.findUsersByIds(conn, List.of(7L))).thenReturn(List.of(user(7L, "alice")));
        when(followCache.batchIsFollowing(7L, List.of(7L))).thenReturn(Collections.emptyMap());

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 1, 10);

        assertEquals(1, page.getList().size());
        // T11-A 评审补强：username 装箱也需有断言（此前随缺省用例一并删除）
        assertEquals("alice", page.getList().get(0).get("username"));
        assertTrue((Boolean) page.getList().get(0).get("isSelf"));
        assertFalse((Boolean) page.getList().get(0).get("isFollowed"));
    }

    @Test
    void getFollowingListPagedWithoutCurrentUserSkipsFollowQuery() throws SQLException {
        // 由原缺省路径用例迁移（T11-A）：currentUserId 为 null 时跳过批量判关注态
        when(followCache.getFollowingWindow(7L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenReturn(List.of(user(8L, "bob")));

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, null, 1, 10);

        assertEquals(1, page.getList().size());
        assertFalse((Boolean) page.getList().get(0).get("isFollowed"));
        assertFalse((Boolean) page.getList().get(0).get("isSelf"));
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }

    @Test
    void getFollowerListPagedEmptyPageSkipsDbAndFollowQuery() throws SQLException {
        // 由原缺省路径用例迁移（T11-A）：空窗口 → 不打事务、不装载、不判关注态
        when(followCache.getFollowerWindow(9L, 0L, 10))
                .thenReturn(new ZSetCache.Window(Collections.emptyList(), 0L));

        PageResult<Map<String, Object>> page = service.getFollowerList(9L, 7L, 1, 10);

        assertTrue(page.getList().isEmpty());
        assertEquals(0, page.getTotal());
        verify(userDao, never()).findUsersByIds(any(), anyList());
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }

    @Test
    void getFollowingListPagedSqlErrorThrowsServerException() throws SQLException {
        when(followCache.getFollowingWindow(7L, 0L, 2))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getFollowingList(7L, 7L, 1, 2));
    }

    /**
     * T12：DB 装载结果为空（该页 ids 指向已删用户）时，仍按**该页 ids** 批量判关注态
     * ——判重口径与改造前逐条一致，不因结果为空而省略缓存读（评审建议补的边界用例）。
     */
    @Test
    void getFollowingListPagedEmptyDbResultStillJudgesThatPage() throws SQLException {
        when(followCache.getFollowingWindow(7L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(8L, 9L), 2L));
        when(userDao.findUsersByIds(conn, List.of(8L, 9L))).thenReturn(Collections.emptyList());
        when(followCache.batchIsFollowing(7L, List.of(8L, 9L))).thenReturn(Map.of(8L, true));

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 1, 10);

        assertTrue(page.getList().isEmpty());
        assertEquals(2, page.getTotal());
        verify(followCache, times(1)).batchIsFollowing(7L, List.of(8L, 9L));
    }

    /**
     * T12（治池 U-14②）：列表装载的批量判关注态必须发生在 DB 事务回调**之外**——
     * DAO 装载在回调内（探针自检，防断言空转），`followCache.batchIsFollowing` 在回调结束后才执行。
     */
    @Test
    void getFollowingListReadsFollowCacheOutsideDbTransaction() throws SQLException {
        when(followCache.getFollowingWindow(7L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenAnswer(inv -> {
            assertTrue(inTransaction[0], "用户行装载应在事务回调内执行");
            return List.of(user(8L, "bob"));
        });
        when(followCache.batchIsFollowing(7L, List.of(8L))).thenAnswer(inv -> {
            assertFalse(inTransaction[0], "关注态缓存批量读不应在事务回调内执行");
            return Map.of(8L, true);
        });

        PageResult<Map<String, Object>> page = service.getFollowingList(7L, 7L, 1, 10);

        assertEquals(1, page.getList().size());
        assertTrue((Boolean) page.getList().get(0).get("isFollowed"));
    }

    /**
     * T12：粉丝列表同路径（共用 {@code loadUserList}）——DB 装载仍在事务回调内，
     * 而 `currentUserId == null` 时**完全不触碰关注态缓存**（早退分支与改造前一致）。
     */
    @Test
    void getFollowerListWithoutCurrentUserLoadsDbInTransactionAndSkipsCache() throws SQLException {
        when(followCache.getFollowerWindow(9L, 0L, 10))
                .thenReturn(new ZSetCache.Window(List.of(8L), 1L));
        when(userDao.findUsersByIds(conn, List.of(8L))).thenAnswer(inv -> {
            assertTrue(inTransaction[0], "用户行装载应在事务回调内执行");
            return List.of(user(8L, "bob"));
        });

        PageResult<Map<String, Object>> page = service.getFollowerList(9L, null, 1, 10);

        assertEquals(1, page.getList().size());
        assertFalse((Boolean) page.getList().get(0).get("isFollowed"));
        verify(followCache, never()).batchIsFollowing(anyLong(), anyList());
    }
}
