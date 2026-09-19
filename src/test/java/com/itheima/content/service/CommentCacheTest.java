package com.itheima.content.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.JacksonCodec;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.comment.dao.CommentDao;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.exception.CacheException;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T10-A 重构后 CommentCache 单测：两键组（主楼 List + 楼中楼 Hash）+ 主楼窗口装载。
 *
 * <p>策略（对齐 CacheAsideTest 真实 Redis 先例）：真实 RedisAccess（本地 6379）+ 真 SingleFlight/
 * JacksonCodec/CacheStats + mock CommentDao（DB 行由预设返回）。每次 setUp flushdb 保证 key 隔离。
 */
class CommentCacheTest {

    private CommentDao commentDao;
    private TransactionTemplate tt;
    private Connection conn;
    private CommentCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        commentDao = mock(CommentDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        RedisAccess redis = new RedisAccess();
        SingleFlight singleFlight = new SingleFlight();
        JacksonCodec codec = new JacksonCodec();
        cache = new CommentCache(commentDao, tt, redis, singleFlight, codec, new CacheStats());

        redis.executeVoid(j -> j.flushDB()); // 清测试 Redis，保证本测试类 key 隔离
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private CommentCacheDTO comment(long id, long contentId, long userId, Long parentId) {
        return new CommentCacheDTO("user" + userId, id, contentId, userId, "msg" + id, parentId, 0);
    }

    // ==================== getRootPage（窗口装载 + 页树 + 真实 total） ====================

    @Test
    void getRootPageLoadsFirstWindowWithRepliesAndTotal() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 3L, 7L, null);
        CommentCacheDTO m2 = comment(2L, 3L, 8L, null);
        CommentCacheDTO m3 = comment(3L, 3L, 9L, null);
        CommentCacheDTO reply = comment(4L, 3L, 9L, 2L); // 挂主楼 2
        when(commentDao.getMainCommentsAfter(conn, 3L, 0L, 2)).thenReturn(List.of(m1, m2));
        when(commentDao.countMainComments(conn, 3L)).thenReturn(3);
        when(commentDao.getRepliesByRootIds(conn, 3L, List.of(1L, 2L))).thenReturn(List.of(reply));

        CommentCache.PageWindow window = cache.getRootPage(3L, 1, 2);

        assertEquals(2, window.getRoots().size());
        assertEquals(1L, window.getRoots().get(0).getCommentId());
        assertTrue(window.getRoots().get(0).getChildren().isEmpty(), "无回复主楼 children 为空数组");
        assertEquals(2L, window.getRoots().get(1).getCommentId());
        assertEquals(List.of(4L), window.getRoots().get(1).getChildren().stream()
                .map(CommentCacheDTO::getCommentId).toList(), "主楼 2 回复随行");
        assertEquals(3, window.getRootTotal(), "total = 真实主楼总数（count key）");
        verify(commentDao).getRepliesByRootIds(eq(conn), eq(3L), anyList());
    }

    @Test
    void getRootPageKeySetAppendsNextWindowWithoutOverlap() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 3L, 7L, null);
        CommentCacheDTO m2 = comment(2L, 3L, 8L, null);
        CommentCacheDTO m3 = comment(3L, 3L, 9L, null);
        when(commentDao.getMainCommentsAfter(conn, 3L, 0L, 2)).thenReturn(List.of(m1, m2));
        when(commentDao.getMainCommentsAfter(conn, 3L, 2L, 2)).thenReturn(List.of(m3));
        when(commentDao.countMainComments(conn, 3L)).thenReturn(3);

        CommentCache.PageWindow page1 = cache.getRootPage(3L, 1, 2);
        CommentCache.PageWindow page2 = cache.getRootPage(3L, 2, 2);

        assertEquals(List.of(1L, 2L), page1.getRoots().stream().map(CommentCacheDTO::getCommentId).toList());
        assertEquals(List.of(3L), page2.getRoots().stream().map(CommentCacheDTO::getCommentId).toList(),
                "第 2 页 keyset 续载（afterCommentId=最后已装主楼），不重不漏");
        assertEquals(3, page2.getRootTotal(), "total 跨页保持真实值");
        verify(commentDao).getMainCommentsAfter(eq(conn), eq(3L), eq(2L), eq(2));
    }

    @Test
    void getRootPageBeyondTailReturnsEmptyPageWithTrueTotal() throws SQLException {
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), eq(0L), anyInt()))
                .thenReturn(List.of(comment(1L, 3L, 7L, null)));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), eq(1L), anyInt())).thenReturn(List.of());
        when(commentDao.countMainComments(conn, 3L)).thenReturn(1);

        CommentCache.PageWindow page = cache.getRootPage(3L, 5, 10);

        assertTrue(page.getRoots().isEmpty(), "越界页返回空列表");
        assertEquals(1, page.getRootTotal(), "信封仍带真实 total");
    }

    // ==================== getFullTree（缺省全量数组 = 两键组全量拼装） ====================

    @Test
    void getFullTreeBuildsAllRootsWithChildren() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 4L, 7L, null);
        CommentCacheDTO m2 = comment(2L, 4L, 8L, null);
        CommentCacheDTO reply = comment(3L, 4L, 9L, 1L);
        when(commentDao.getMainCommentsAfter(eq(conn), eq(4L), eq(0L), anyInt())).thenReturn(List.of(m1, m2));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(4L), eq(2L), anyInt())).thenReturn(List.of());
        when(commentDao.countMainComments(conn, 4L)).thenReturn(2);
        when(commentDao.getRepliesByRootIds(conn, 4L, List.of(1L, 2L))).thenReturn(List.of(reply));

        List<CommentCacheDTO> tree = cache.getFullTree(4L);

        assertEquals(2, tree.size());
        assertEquals(1L, tree.get(0).getCommentId());
        assertEquals(List.of(3L), tree.get(0).getChildren().stream()
                .map(CommentCacheDTO::getCommentId).toList());
        assertTrue(tree.get(1).getChildren().isEmpty());
    }

    @Test
    void getFullTreeNoCommentsReturnsNullAndMarksEmpty() throws SQLException {
        when(commentDao.getMainCommentsAfter(conn, 3L, 0L, 200)).thenReturn(List.of());
        when(commentDao.countMainComments(conn, 3L)).thenReturn(0);

        assertNull(cache.getFullTree(3L), "已确认无评论 → null（hit-empty 语义）");

        // 第二次读命中空标记，不再查 DB
        assertNull(cache.getFullTree(3L));
        verify(commentDao, times(1)).getMainCommentsAfter(eq(conn), eq(3L), eq(0L), anyInt());
    }

    // ==================== 楼中楼懒载（HMGET field 缺失 → 单飞 DB 分组回填） ====================

    @Test
    void repliesAreLazilyLoadedAndCached() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 5L, 7L, null);
        CommentCacheDTO reply = comment(2L, 5L, 8L, 1L);
        when(commentDao.getMainCommentsAfter(eq(conn), eq(5L), eq(0L), anyInt())).thenReturn(List.of(m1));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(5L), eq(1L), anyInt())).thenReturn(List.of());
        when(commentDao.getRepliesByRootIds(conn, 5L, List.of(1L))).thenReturn(List.of(reply));

        List<CommentCacheDTO> first = cache.getFullTree(5L);
        List<CommentCacheDTO> second = cache.getFullTree(5L);

        assertEquals(1, first.get(0).getChildren().size());
        assertEquals(1, second.get(0).getChildren().size());
        // 第二次读命中 Hash field，不再查 DB
        verify(commentDao, times(1)).getRepliesByRootIds(eq(conn), eq(5L), anyList());
    }

    // ==================== 失效重映射（增量 / 删回复 / 点赞 → 定向 HDEL field） ====================

    @Test
    void invalidateReplyUnderRefreshesFieldOnNextRead() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 6L, 7L, null);
        when(commentDao.getMainCommentsAfter(eq(conn), eq(6L), eq(0L), anyInt())).thenReturn(List.of(m1));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(6L), eq(1L), anyInt())).thenReturn(List.of());
        when(commentDao.getRepliesByRootIds(conn, 6L, List.of(1L)))
                .thenReturn(List.of(comment(2L, 6L, 8L, 1L)))
                .thenReturn(List.of(comment(2L, 6L, 8L, 1L), comment(3L, 6L, 9L, 1L)));

        cache.getFullTree(6L); // 装载并缓存 replies field
        cache.invalidateReplyUnder(6L, 1L); // 模拟回复增删
        List<CommentCacheDTO> refreshed = cache.getFullTree(6L);

        assertEquals(2, refreshed.get(0).getChildren().size(), "HDEL 后懒载刷新最新 children");
        verify(commentDao, times(2)).getRepliesByRootIds(eq(conn), eq(6L), anyList());
    }

    @Test
    void invalidateRootsReloadsWindowAndCount() throws SQLException {
        when(commentDao.getMainCommentsAfter(conn, 3L, 0L, 2)).thenReturn(List.of(comment(1L, 3L, 7L, null)));
        when(commentDao.countMainComments(conn, 3L)).thenReturn(1);

        cache.getRootPage(3L, 1, 2);
        cache.invalidateRoots(3L); // 新增/删除主楼后
        CommentCache.PageWindow window = cache.getRootPage(3L, 1, 2);

        assertEquals(1, window.getRoots().size());
        // 失效后读懒重建窗口
        verify(commentDao, times(2)).getMainCommentsAfter(eq(conn), eq(3L), eq(0L), eq(2));
    }

    @Test
    void invalidateCommentsDeletesAllKeys() throws SQLException {
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), eq(0L), anyInt())).thenReturn(
                List.of(comment(1L, 3L, 7L, null)));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), longThat(after -> after > 0L), anyInt()))
                .thenReturn(List.of());

        cache.getRootPage(3L, 1, 10);
        cache.invalidateComments(3L);

        // 清除后读触发重新装载（缓存 key 已 DEL；此处验证无异常且数据可重建）
        assertEquals(1, cache.getRootPage(3L, 1, 10).getRoots().size());
    }

    // ==================== notifyCommentLikeChanged（定位 contentId + 主楼 → 定向 HDEL） ====================

    @Test
    void notifyCommentLikeChangedResolvesRootAndInvalidatesField() throws SQLException {
        CommentCacheDTO m1 = comment(1L, 3L, 7L, null);
        when(commentDao.getContentIdByCommentId(conn, 9L)).thenReturn(3L);
        when(commentDao.getRootIdByCommentId(conn, 9L)).thenReturn(2L);
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), eq(0L), anyInt())).thenReturn(List.of(m1, comment(2L, 3L, 8L, null)));
        when(commentDao.getMainCommentsAfter(eq(conn), eq(3L), longThat(after -> after > 0L), anyInt()))
                .thenReturn(List.of());
        when(commentDao.getRepliesByRootIds(eq(conn), eq(3L), anyList()))
                .thenReturn(List.of(comment(4L, 3L, 9L, 2L)));

        cache.getRootPage(3L, 1, 10); // 先装载（缓存 replies field）
        cache.notifyCommentLikeChanged(9L); // 点赞 → HDEL 主楼 2 的 field

        // 再读触发懒载刷新（verify 调用次数增长）
        cache.getRootPage(3L, 1, 10);
        verify(commentDao, times(2)).getRepliesByRootIds(eq(conn), eq(3L), anyList());
    }

    @Test
    void notifyCommentLikeChangedMissingCommentDoesNothing() throws SQLException {
        when(commentDao.getContentIdByCommentId(conn, 999L)).thenReturn(null);
        when(commentDao.getRootIdByCommentId(conn, 999L)).thenReturn(null);

        assertDoesNotThrow(() -> cache.notifyCommentLikeChanged(999L));
    }

    // ==================== Redis 异常降级（走 DB，不写回） ====================

    @Test
    void getFullTreeDegradesToDbWhenRedisFails() throws SQLException {
        RedisAccess brokenRedis = mock(RedisAccess.class);
        when(brokenRedis.execute(any(Function.class))).thenThrow(new CacheException("redis down"));
        doThrow(new CacheException("redis down")).when(brokenRedis).executeVoid(any(Consumer.class));
        CommentCache degraded = new CommentCache(commentDao, tt, brokenRedis, new SingleFlight(),
                new JacksonCodec(), new CacheStats());

        CommentCacheDTO m1 = comment(1L, 3L, 7L, null);
        when(commentDao.getMainCommentsAfter(conn, 3L, 0L, 200)).thenReturn(List.of(m1));

        List<CommentCacheDTO> tree = degraded.getFullTree(3L);

        assertEquals(1, tree.size(), "Redis 异常降级走 DB（不写回）");
        assertTrue(tree.get(0).getChildren().isEmpty());
    }

    // ==================== collectCommentIds（保留，行为不变） ====================

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