package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.ZSetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedInboxReader} 单测（feed2-23 T23）：读态闸门（{@code feed_inbox_sync} 存在性）/ 
 * 收件箱腿（缓存三态 + DB 回源 + 升序反转为降序）/ 失败包装（SEVERE + 栈 + {@code ServerException}）。
 *
 * <p>隔离手法：mock {@link FeedInboxDao} / {@link ZSetCache} / {@link TransactionTemplate}
 * （回调打到 mock {@link Connection}），**不依赖真实 Redis / DB**。
 */
class FeedInboxReaderTest {

    private static final long USER = 7L;

    private FeedInboxDao feedInboxDao;
    private ZSetCache zSetCache;
    private TransactionTemplate transactionTemplate;
    private Connection conn;
    private FeedInboxReader reader;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        feedInboxDao = mock(FeedInboxDao.class);
        zSetCache = mock(ZSetCache.class);
        transactionTemplate = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        reader = new FeedInboxReader(feedInboxDao, zSetCache, transactionTemplate);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedInboxReader.class));
        when(transactionTemplate.execute(any(TransactionTemplate.TransactionAction.class)))
                .thenAnswer(inv -> {
                    TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
                    return action.execute(conn);
                });
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    // ==================== 读态闸门 ====================

    @Test
    void isSyncedTrueWhenRowExists() throws SQLException {
        when(feedInboxDao.existsSync(conn, USER)).thenReturn(true);

        assertTrue(reader.isSynced(USER), "feed_inbox_sync 有行 ⇒ 已同步（窗口可读）");
        assertTrue(probe.records().isEmpty(), "查得/查不到都属常态，不应记日志");
    }

    @Test
    void isSyncedFalseWhenNoRow() throws SQLException {
        when(feedInboxDao.existsSync(conn, USER)).thenReturn(false);

        assertFalse(reader.isSynced(USER), "无行 ⇒ 未同步（调用方回退纯拉）");
    }

    @Test
    void isSyncedWrapsSqlFailureAsServerExceptionWithSingleStackedRecord() throws SQLException {
        when(feedInboxDao.existsSync(conn, USER)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> reader.isSynced(USER));
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "读侧收件箱同步状态查询失败, userId=" + USER, SQLException.class);
    }

    // ==================== 收件箱腿 ====================

    @Test
    void readInboxUsesInboxKeyAndInboxTtl() {
        when(zSetCache.getMembers(eq(CacheKeys.feedInbox(USER)), any(), anyLong()))
                .thenReturn(List.of(1L));

        reader.readInbox(USER);

        verify(zSetCache).getMembers(eq(CacheKeys.feedInbox(USER)), any(),
                eq(AppConfig.getFeedInboxTtlSeconds()));
    }

    @Test
    void readInboxReversesAscendingToDescending() {
        // 缓存 / 回源两条路径都是 contentId 升序（ZRANGE 序 = score 序）⇒ 反序即"内容倒序"
        when(zSetCache.getMembers(eq(CacheKeys.feedInbox(USER)), any(), anyLong()))
                .thenReturn(List.of(1L, 2L, 3L));

        assertEquals(List.of(3L, 2L, 1L), reader.readInbox(USER));
    }

    @Test
    void readInboxEmptyReturnsEmpty() {
        when(zSetCache.getMembers(eq(CacheKeys.feedInbox(USER)), any(), anyLong()))
                .thenReturn(List.of());

        assertTrue(reader.readInbox(USER).isEmpty(), "空标记 / 空窗口 ⇒ 空列表（不再打 DB）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboxDbLoaderWrapsSqlFailureAndLogsSingleStackedRecord() throws SQLException {
        when(zSetCache.getMembers(eq(CacheKeys.feedInbox(USER)), any(), anyLong()))
                .thenReturn(List.of());
        when(feedInboxDao.findInboxContentIds(conn, USER)).thenThrow(new SQLException("db down"));
        ArgumentCaptor<Supplier<Collection<Long>>> loader =
                ArgumentCaptor.forClass((Class) Supplier.class);

        reader.readInbox(USER);   // 缓存桩不触发 loader
        verify(zSetCache).getMembers(eq(CacheKeys.feedInbox(USER)), loader.capture(), anyLong());

        assertThrows(ServerException.class, () -> loader.getValue().get());
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "收件箱窗口 DB 装载失败, userId=" + USER, SQLException.class);
    }
}
