package com.itheima.feed.service;

import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.ZSetCache;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedInboxWriter} 单测（feed2-21 T21；由 feed1-18 T18 的 FeedInboxCacheTest 改写）：
 * 窗口迭代 / DB 真相批量落库 / 写后失效 DEL / 大V跳过 / 三类降级 / FEED 域打点。
 *
 * <p>隔离手法：mock {@link FollowCache}（窗口读）/ {@link FeedInboxDao}（落库）/ {@link TransactionTemplate}
 * （回调打到 mock {@link Connection}）/ {@link FeedBigVRouter}（大V判定）/ {@link RedisAccess}
 * （回调打到 mock {@link Jedis}），**不依赖真实 Redis / DB / broker**；{@link CacheStats} 用真实件，
 * 以便断言"FEED 域可辨"。交互序列记入 {@code events}，用于断言"先 DB 真相、后缓存失效"。
 */
class FeedInboxWriterTest {

    private static final long AUTHOR = 9L;
    private static final long CONTENT = 42L;
    private static final int BATCH = FeedInboxWriter.FANOUT_BATCH;

    private FollowCache followCache;
    private FeedInboxDao feedInboxDao;
    private TransactionTemplate transactionTemplate;
    private FeedBigVRouter bigVRouter;
    private RedisAccess redis;
    private Jedis jedis;
    private Connection conn;
    private CacheStats stats;
    private FeedInboxWriter writer;
    private LogProbe probe;

    /** 交互序列（断言"先 DB 真相、后缓存失效"的顺序）。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        followCache = mock(FollowCache.class);
        feedInboxDao = mock(FeedInboxDao.class);
        transactionTemplate = mock(TransactionTemplate.class);
        bigVRouter = mock(FeedBigVRouter.class);
        redis = mock(RedisAccess.class);
        jedis = mock(Jedis.class);
        conn = mock(Connection.class);
        stats = new CacheStats();
        events.clear();

        writer = new FeedInboxWriter(followCache, feedInboxDao, transactionTemplate, bigVRouter, redis, stats);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedInboxWriter.class));

        when(transactionTemplate.execute(any(TransactionTemplate.TransactionAction.class)))
                .thenAnswer(inv -> {
                    events.add("DB");
                    TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
                    return action.execute(conn);
                });
        doAnswer(inv -> {
            events.add("DEL");
            Consumer<Jedis> action = inv.getArgument(0);
            action.accept(jedis);
            return null;
        }).when(redis).executeVoid(any());
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    // ==================== 正常路径 ====================

    @Test
    void fanoutWritesDbTruthThenInvalidatesCacheForEachFan() throws SQLException {
        stubWindows(List.of(List.of(11L, 12L)));

        writer.fanout(CONTENT, AUTHOR);

        // ① DB 真相：一批粉丝 = 一条 INSERT IGNORE（含 contentId）
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, List.of(11L, 12L));
        // ② 写后失效：一次 DEL 多键（每粉丝 4 键 = 收件箱缓存三件套 + 遗留完整态标记）
        verify(jedis).del(CacheKeys.feedInbox(11L), CacheKeys.empty(CacheKeys.feedInbox(11L)),
                CacheKeys.partial(CacheKeys.feedInbox(11L)), CacheKeys.feedInboxFull(11L),
                CacheKeys.feedInbox(12L), CacheKeys.empty(CacheKeys.feedInbox(12L)),
                CacheKeys.partial(CacheKeys.feedInbox(12L)), CacheKeys.feedInboxFull(12L));
        // 顺序红线：先 DB 真相、后缓存失效（读 miss 回源才有意义）
        assertEquals(List.of("DB", "DEL"), events);
        // 一批粉丝 = 一次 DB 连接 + 一次 Redis 连接（不是逐粉丝各借还一次）
        verify(transactionTemplate, times(1)).execute(any());
        verify(redis, times(1)).executeVoid(any());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutIteratesAllWindowsUntilShortPage() throws SQLException {
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH * 2);
        List<Long> third = ids(BATCH * 2 + 1, BATCH * 2 + 40);
        stubWindows(List.of(first, second, third));

        writer.fanout(CONTENT, AUTHOR);

        verify(followCache).getFollowerWindow(AUTHOR, 0L, BATCH);
        verify(followCache).getFollowerWindow(AUTHOR, BATCH, BATCH);
        verify(followCache).getFollowerWindow(AUTHOR, (long) BATCH * 2, BATCH);
        verify(followCache, times(3)).getFollowerWindow(eq(AUTHOR), anyLong(), eq(BATCH));
        // 每批一条落库 + 一次失效；末批（40 条 < BATCH）也必须写完
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, third);
        verify(transactionTemplate, times(3)).execute(any());
        verify(redis, times(3)).executeVoid(any());
        assertEquals(List.of("DB", "DEL", "DB", "DEL", "DB", "DEL"), events);
    }

    @Test
    void fanoutStopsWhenFirstWindowEmpty() {
        stubWindows(List.of(emptyPage()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followCache, times(1)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
        verify(redis, never()).executeVoid(any());
    }

    @Test
    void fanoutStopsAfterExactBatchFollowedByEmptyWindow() throws SQLException {
        // 边界：粉丝数恰为 BATCH 的整数倍 —— 靠"下一窗空"终止，不得死循环
        stubWindows(List.of(ids(1, BATCH), List.of()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followCache).getFollowerWindow(AUTHOR, BATCH, BATCH);
        verify(followCache, times(2)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, times(1)).execute(any());
        verify(redis, times(1)).executeVoid(any());
    }

    @Test
    void fanoutTouchesOnlyFeedInboxKeys() {
        // 红线：不改读路径 —— 只碰 feed:inbox:* 及其派生标记（empty:/partial:/full:），不写 content:* / user:*
        stubWindows(List.of(List.of(11L, 12L, 13L)));

        writer.fanout(CONTENT, AUTHOR);

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(jedis).del(keys.capture());
        assertEquals(12, keys.getValue().length, "3 粉丝 × 4 键（三件套 + 标记）");
        for (String key : keys.getValue()) {
            String dataKey = key.startsWith("empty:") ? key.substring("empty:".length())
                    : key.startsWith("partial:") ? key.substring("partial:".length()) : key;
            assertTrue(dataKey.startsWith(CacheKeys.FEED_INBOX_PREFIX), "越界 key: " + key);
        }
    }

    // ==================== 大V路由（T21） ====================

    @Test
    void fanoutSkipsBigVAuthorWithoutAnyDbOrCacheTouch() {
        when(bigVRouter.isBigV(AUTHOR)).thenReturn(true);
        // 防御：即使窗口可返回，也不得被读取（大V在窗口迭代之前就返回）
        stubWindows(List.of(List.of(11L)));

        writer.fanout(CONTENT, AUTHOR);

        verify(bigVRouter).isBigV(AUTHOR);
        verify(followCache, never()).getFollowerWindow(anyLong(), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
        verify(redis, never()).executeVoid(any());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "大V跳过属常态路由，不应记 WARNING");
    }

    // ==================== 降级路径 ====================

    @Test
    void fanoutDegradesOnFollowerWindowFailureWithoutDoubleStack() {
        when(followCache.getFollowerWindow(eq(AUTHOR), anyLong(), anyInt()))
                .thenThrow(new DatabaseException("db down"));

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散中止"));
        assertNull(warnings.getFirst().getThrown(), "源头（FollowCache.loadIds）已持栈 → 此处为结论行、不带栈");
        verify(transactionTemplate, never()).execute(any());
        verify(redis, never()).executeVoid(any());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutDegradesOnDbWriteFailureAndStopsIterating() throws SQLException {
        // DB 全不可用时，不得把"窗口 DB 查 + 降级日志"按批数各刷一遍：首批落库失败即短路本次 fanout
        stubWindows(List.of(ids(1, BATCH), ids(BATCH + 1, BATCH + 10)));
        when(feedInboxDao.insertIgnoreBatch(eq(conn), eq(CONTENT), any()))
                .thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(followCache, times(1)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, times(1)).execute(any());
        verify(redis, never()).executeVoid(any());
        List<LogRecord> severe = probe.atLevel(Level.SEVERE);
        assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertNotNull(severe.getFirst().getThrown(), "落库失败是该链唯一捕获点 → 持栈");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), "结论行恰一条（不带栈）");
        assertNull(warnings.getFirst().getThrown());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL), "DB 失败不记缓存 WRITE_FAIL");
    }

    @Test
    void fanoutDegradesOnDelFailureAbandonsInvalidationButKeepsWritingDb() throws SQLException {
        // DEL 失败不停写（DB 真相优先）：两批都落库；失效通道**首次失败即停用**（后续批次不再尝试），
        // WARNING + 打点只记一次（不按批刷）
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH + 10);
        stubWindows(List.of(first, second));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(transactionTemplate, times(2)).execute(any());
        verify(redis, times(1)).executeVoid(any());   // 首次失败即停用失效通道（同 T18"不放大依赖故障"）
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING（首次），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散收件箱缓存失效失败"));
        assertNotNull(warnings.getFirst().getThrown(), "DEL 失败是该链唯一捕获点 → 必须持栈");
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL), "批量失败记一次（不逐批刷）");
    }

    @Test
    void fanoutNeverThrowsOnUnexpectedError() {
        // 契约"绝不抛"的最后兜底：异常不得穿透消费容器去转死信
        when(bigVRouter.isBigV(AUTHOR)).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        List<LogRecord> severe = probe.atLevel(Level.SEVERE);
        assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severe.getFirst().getMessage().contains("写扩散异常（已兜底"));
        assertNotNull(severe.getFirst().getThrown());
    }

    // ==================== 辅助 ====================

    private void stubWindows(List<List<Long>> pages) {
        List<ZSetCache.Window> windows = new ArrayList<>();
        for (List<Long> page : pages) {
            windows.add(new ZSetCache.Window(page, page.size()));
        }
        when(followCache.getFollowerWindow(eq(AUTHOR), anyLong(), eq(BATCH)))
                .thenReturn(windows.get(0), windows.subList(1, windows.size()).toArray(new ZSetCache.Window[0]));
    }

    /** 空窗口页（显式类型，避免 List.of() 嵌套泛型推断歧义）。 */
    private static List<Long> emptyPage() {
        return new ArrayList<>();
    }

    private static List<Long> ids(int fromInclusive, int toInclusive) {
        List<Long> list = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            list.add((long) i);
        }
        return list;
    }
}