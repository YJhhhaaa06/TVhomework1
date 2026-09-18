package com.itheima.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.comment.dao.CommentDao;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.exception.DatabaseException;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentCacheTest {

    private CommentDao commentDao;
    private CacheAside cacheAside;
    private TransactionTemplate tt;
    private Connection conn;
    private CommentCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        commentDao = mock(CommentDao.class);
        cacheAside = mock(CacheAside.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        cache = new CommentCache(commentDao, tt, cacheAside);

        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
        // getCommentTree 走 CacheAside.get，测试中直接真实执行 loader（三态 miss/hit-empty/hit-data 语义由 CacheAside 内部保证，见 CacheAsideTest）
        when(cacheAside.get(anyString(), any(TypeReference.class), any(Callable.class), anyLong())).thenAnswer(inv -> {
            Callable<List<CommentCacheDTO>> loader = inv.getArgument(2);
            return loader.call();
        });
    }

    private CommentCacheDTO comment(long id, long contentId, long userId, Long parentId) {
        return new CommentCacheDTO("user" + userId, id, contentId, userId, "msg" + id, parentId, 0);
    }

    // ==================== getCommentTree（loader：DB 装载 + 归一化） ====================

    @Test
    void getCommentTreeBuildsNormalizedTreeFromDb() throws SQLException {
        CommentCacheDTO main = comment(1L, 3L, 7L, null);
        CommentCacheDTO reply = comment(2L, 3L, 8L, 1L);
        CommentCacheDTO deepReply = comment(3L, 3L, 9L, 2L); // 回复的回复 → 平铺挂主楼
        when(commentDao.getComments(conn, 3L)).thenReturn(List.of(main, reply, deepReply));

        List<CommentCacheDTO> tree = cache.getCommentTree(3L);

        assertEquals(1, tree.size());
        assertEquals(1L, tree.get(0).getCommentId());
        assertEquals(2, tree.get(0).getChildren().size(), "回复的回复也应平铺挂到主楼下");
    }

    @Test
    void getCommentTreeNoCommentsReturnsNull() throws SQLException {
        when(commentDao.getComments(conn, 3L)).thenReturn(List.of());

        assertNull(cache.getCommentTree(3L));
    }

    @Test
    void getCommentTreeSqlErrorThrowsDatabaseException() throws SQLException {
        // 三期 T3：DB 查询失败 = 加载失败（抛 DatabaseException），不再是"确认无数据"→ 不写空标记
        when(commentDao.getComments(conn, 3L)).thenThrow(new SQLException("db down"));

        assertThrows(DatabaseException.class, () -> cache.getCommentTree(3L));
    }

    @Test
    void getCommentTreeDbErrorThrowsDatabaseException() throws SQLException {
        // 三期 T3：意外异常按加载失败处理（包成 DatabaseException），不静默污染空标记
        when(commentDao.getComments(conn, 3L)).thenThrow(new RuntimeException("connection lost"));

        assertThrows(DatabaseException.class, () -> cache.getCommentTree(3L));
    }

    @Test
    void getCommentTreeUsesContentCommentsKeyAndCommentTtl() throws SQLException {
        when(commentDao.getComments(conn, 5L)).thenReturn(List.of(comment(1L, 5L, 7L, null)));

        cache.getCommentTree(5L);

        verify(cacheAside).get(eq(CacheKeys.contentComments(5L)), any(TypeReference.class), any(Callable.class), anyLong());
    }

    // ==================== invalidateComments（业务显式失效 4.5） ====================

    @Test
    void invalidateCommentsDeletesTreeKeyAndEmptyMarker() {
        cache.invalidateComments(3L);

        verify(cacheAside).invalidate(CacheKeys.contentComments(3L));
    }

    // ==================== notifyCommentLikeChanged（定位 contentId 后失效） ====================

    @Test
    void notifyCommentLikeChangedResolvesContentAndInvalidates() throws SQLException {
        when(commentDao.getContentIdByCommentId(conn, 9L)).thenReturn(3L);

        cache.notifyCommentLikeChanged(9L);

        verify(cacheAside).invalidate(CacheKeys.contentComments(3L));
    }

    @Test
    void notifyCommentLikeChangedMissingCommentDoesNothing() throws SQLException {
        when(commentDao.getContentIdByCommentId(conn, 999L)).thenReturn(null);

        cache.notifyCommentLikeChanged(999L);

        verify(cacheAside, never()).invalidate(anyString());
    }

    @Test
    void notifyCommentLikeChangedDbErrorDoesNothing() throws SQLException {
        when(commentDao.getContentIdByCommentId(conn, 9L)).thenThrow(new SQLException("db down"));

        cache.notifyCommentLikeChanged(9L);

        verify(cacheAside, never()).invalidate(anyString());
    }

    // ==================== collectCommentIds ====================

    @Test
    void collectCommentIdsFlattensTreeWithChildren() {
        CommentCacheDTO root = comment(1L, 3L, 7L, null);
        CommentCacheDTO child = comment(2L, 3L, 8L, 1L);
        CommentCacheDTO grandChild = comment(3L, 3L, 9L, 2L);
        child.setChildren(List.of(grandChild));
        root.setChildren(List.of(child));

        List<Long> ids = cache.collectCommentIds(List.of(root));

        assertEquals(List.of(1L, 2L, 3L), ids);
    }

    @Test
    void collectCommentIdsEmptyTreeReturnsEmpty() {
        assertTrue(cache.collectCommentIds(List.of()).isEmpty());
    }
}