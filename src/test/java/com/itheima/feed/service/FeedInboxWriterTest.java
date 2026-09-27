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
 * {@link FeedInboxWriter} 单测（feed2-21 T21；由 feed1-18 T18 的 FeedInboxCacheTest 改写；
 * feed2-23 T23 增"大V发件箱写后失效"）：窗口迭代 / DB 真相批量落库 / 写后失效 DEL（outbox + 收件箱）/
 * 大V跳过 / 三类降级 / FEED 域打点。
 *
 * <p>隔离手法：mock {@link FollowCache}（窗口读）/ {@link FeedInboxDao}（落库）/ {@link TransactionTemplate}
 * （回调打到 mock {@link Connection}）/ {@link FeedBigVRouter}（大V判定）/ {@link RedisAccess}
 * （回调打到 mock {@link Jedis}），**不依赖真实 Redis / DB / broker**；{@link CacheStats} 用真实件，
 * 以便断言"FEED 域可辨"。交互序列记入 {@code events}，用于断言"outbox 失效最前、DB 真相在中、
 * 收件箱失效在后"的顺序红线。
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

    /** 交互序列（断言"outbox 失效最前 → DB 真相 → 收件箱失效"的顺序红线）。 */
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
        stubRedisDelSucceeds();
    }

    /** Redis 失效通道默认可用：每次 {@code executeVoid} 记一条 "DEL" 并执行回调。 */
    @SuppressWarnings("unchecked")
    private void stubRedisDelSucceeds() {
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
    void fanoutInvalidatesOutboxThenWritesDbThenInvalidatesInbox() throws SQLException {
        stubWindows(List.of(List.of(11L, 12L)));

        writer.fanout(CONTENT, AUTHOR);

        // ① outbox 写后失效（两件套）——**最前**，先于大V判定与落库
        verify(jedis).del(CacheKeys.feedOutbox(AUTHOR), CacheKeys.empty(CacheKeys.feedOutbox(AUTHOR)));
        // ② DB 真相：一批粉丝 = 一条 INSERT IGNORE（含 contentId）
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, List.of(11L, 12L));
        // ③ 收件箱写后失效：一次 DEL 多键（每粉丝 3 键 = 三件套；完整态标记已随 T22 退役）
        verify(jedis).del(CacheKeys.feedInbox(11L), CacheKeys.empty(CacheKeys.feedInbox(11L)),
                CacheKeys.partial(CacheKeys.feedInbox(11L)),
                CacheKeys.feedInbox(12L), CacheKeys.empty(CacheKeys.feedInbox(12L)),
                CacheKeys.partial(CacheKeys.feedInbox(12L)));
        // 顺序红线：outbox 失效最前 → DB 真相 → 收件箱失效（读 miss 回源才有意义）
        assertEquals(List.of("DEL", "DB", "DEL"), events);
        // 一批粉丝 = 一次 DB 连接 + 两次 Redis 连接（outbox 一次、收件箱一次；不逐粉丝各借还）
        verify(transactionTemplate, times(1)).execute(any());
        verify(redis, times(2)).executeVoid(any());
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
        // 1 次 outbox 失效 + 3 次收件箱失效
        verify(redis, times(4)).executeVoid(any());
        assertEquals(List.of("DEL", "DB", "DEL", "DB", "DEL", "DB", "DEL"), events);
    }

    @Test
    void fanoutStopsWhenFirstWindowEmpty() {
        stubWindows(List.of(emptyPage()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followCache, times(1)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
        // 窗口为空 ⇒ 无粉丝可失效；但 outbox 失效**照旧执行**（发表者本人的发件箱要先失效）
        verify(jedis, times(1)).del(CacheKeys.feedOutboxCacheKeys(AUTHOR));
        verify(redis, times(1)).executeVoid(any());
    }

    @Test
    void fanoutStopsAfterExactBatchFollowedByEmptyWindow() throws SQLException {
        // 边界：粉丝数恰为 BATCH 的整数倍 —— 靠"下一窗空"终止，不得死循环
        stubWindows(List.of(ids(1, BATCH), List.of()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followCache).getFollowerWindow(AUTHOR, BATCH, BATCH);
        verify(followCache, times(2)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, times(1)).execute(any());
        verify(redis, times(2)).executeVoid(any());   // outbox + 首批收件箱
    }

    @Test
    void fanoutTouchesOnlyFeedKeys() {
        // 红线：不改读路径 —— 只碰 feed:outbox:* / feed:inbox:* 及其派生标记（empty:/partial:），
        //       不写 content:* / user:*
        stubWindows(List.of(List.of(11L, 12L, 13L)));

        writer.fanout(CONTENT, AUTHOR);

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(jedis, times(2)).del(keys.capture());
        List<String[]> calls = keys.getAllValues();

        assertEquals(2, calls.get(0).length, "第一次 DEL = outbox 两件套");
        for (String key : calls.get(0)) {
            String dataKey = stripMarkerPrefix(key);
            assertTrue(dataKey.startsWith(CacheKeys.FEED_OUTBOX_PREFIX), "越界 key: " + key);
        }

        assertEquals(9, calls.get(1).length, "第二次 DEL = 3 粉丝 × 3 键（三件套；完整态标记已随 T22 退役）");
        for (String key : calls.get(1)) {
            String dataKey = stripMarkerPrefix(key);
            assertTrue(dataKey.startsWith(CacheKeys.FEED_INBOX_PREFIX), "越界 key: " + key);
        }
    }

    // ==================== 大V路由（T21；T23 补 outbox 失效顺序） ====================

    @Test
    void fanoutInvalidatesOutboxBeforeBigVEarlyReturn() {
        when(bigVRouter.isBigV(AUTHOR)).thenReturn(true);
        // 防御：即使窗口可返回，也不得被读取（大V在窗口迭代之前就返回）
        stubWindows(List.of(List.of(11L)));

        writer.fanout(CONTENT, AUTHOR);

        verify(bigVRouter).isBigV(AUTHOR);
        // T23 核心：大V发布**必须**失效自己的发件箱（这正是读侧唯一会读它的场景）
        verify(jedis, times(1)).del(CacheKeys.feedOutboxCacheKeys(AUTHOR));
        assertEquals(List.of("DEL"), events);
        verify(followCache, never()).getFollowerWindow(anyLong(), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
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
        verify(redis, times(1)).executeVoid(any());   // 仅 outbox 失效（成功，无日志）
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
        verify(redis, times(1)).executeVoid(any());   // 仅 outbox 失效（先于落库，已成功）
        List<LogRecord> severe = probe.atLevel(Level.SEVERE);
        assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertNotNull(severe.getFirst().getThrown(), "落库失败是该链唯一捕获点 → 持栈");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), "结论行恰一条（不带栈）");
        assertNull(warnings.getFirst().getThrown());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL), "DB 失败不记缓存 WRITE_FAIL");
    }

    @Test
    void fanoutOutboxDelFailureSuppressesLaterInvalidationWithOneStackedWarning() throws SQLException {
        // 首次失效（outbox）就遇到 Redis 不可用 ⇒ 后续收件箱失效**不再尝试**（不放大依赖故障），
        // 但 DB 真相一路写到底；WARNING + 打点只记一次
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH + 10);
        stubWindows(List.of(first, second));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(transactionTemplate, times(2)).execute(any());
        verify(redis, times(1)).executeVoid(any());
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING（首次），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("大V发件箱缓存失效失败"));
        assertNotNull(warnings.getFirst().getThrown(), "该链唯一捕获点 → 必须持栈");
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL), "批量失败记一次（不逐批刷）");
    }

    @Test
    void fanoutAbandonsInboxInvalidationAfterFirstDelFailure() throws SQLException {
        // outbox 失效成功、首批收件箱失效失败 ⇒ 后续批次的收件箱失效停用（DB 照写）
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH + 10);
        stubWindows(List.of(first, second));
        doAnswer(inv -> {
            events.add("DEL");
            Consumer<Jedis> action = inv.getArgument(0);
            action.accept(jedis);
            return null;
        }).doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(redis, times(2)).executeVoid(any());   // outbox + 首批收件箱（失败后停用）
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING（首次），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散收件箱缓存失效失败"));
        assertNotNull(warnings.getFirst().getThrown(), "该链唯一捕获点 → 必须持栈");
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
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

    /** 剥掉 {@code empty:} / {@code partial:} 标记前缀，取回内层数据 key（越界断言用）。 */
    private static String stripMarkerPrefix(String key) {
        if (key.startsWith("empty:")) {
            return key.substring("empty:".length());
        }
        if (key.startsWith("partial:")) {
            return key.substring("partial:".length());
        }
        return key;
    }

    private static List<Long> ids(int fromInclusive, int toInclusive) {
        List<Long> list = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            list.add((long) i);
        }
        return list;
    }
}
