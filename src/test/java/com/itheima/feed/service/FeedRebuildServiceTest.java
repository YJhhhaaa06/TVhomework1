package com.itheima.feed.service;

import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FeedRebuildService} 单测（feed1-19 T19）：三步顺序（红线）/ 去重锁 / 分页到底 /
 * 两类降级（Redis、DB）/ 锁释放 / 只碰 FEED 域 key / 并发仅一次实际重建。
 *
 * <p>隔离手法：mock {@link FollowDao} / {@link ContentDao} / {@link TransactionTemplate} /
 * {@link RedisAccess}（回调打到 mock {@link Jedis} + 两个 mock {@link Pipeline} 上，**不依赖真实
 * Redis / DB / broker**）；{@link CacheStats} 用真实件以断言"FEED 域可辨"。
 *
 * <p>两个 pipeline 分开：{@code pipelined()} 第 1 次调用 = 重建第①步的 DEL，之后 = 第③步的分批
 * ZADD。交互序列记入 {@code events}，用于断言**三步顺序不得调换**（DEL → DB 重查 → ZADD → 标记）。
 */
class FeedRebuildServiceTest {

    private static final long USER = 7L;
    private static final long FOLLOWED = 8L;
    /** app.properties 的 feed.inbox.ttlMinutes 默认 60min（AppConfig 带默认值读取）。 */
    private static final long EXPECTED_TTL_SECONDS = 60L * 60;
    private static final int PAGE = FeedRebuildService.REBUILD_PAGE_SIZE;
    private static final int THREADS = 16;

    private static final String DEL_INBOX = "DEL:" + "feed:inbox:" + USER;
    private static final String DEL_MARKER = "DEL:" + "feed:inbox:full:" + USER;
    private static final String DB = "DB";
    private static final String ZADD = "ZADD:" + "feed:inbox:" + USER;
    private static final String MARKER = "MARKER:" + "feed:inbox:full:" + USER;

    private FollowDao followDao;
    private ContentDao contentDao;
    private TransactionTemplate transactionTemplate;
    private RedisAccess redis;
    private Jedis jedis;
    private Pipeline delPipeline;
    private Pipeline writePipeline;
    private CacheStats stats;
    private Connection conn;
    private FeedRebuildService service;
    private LogProbe probe;

    /** 交互序列（断言顺序用；并发用例下多线程写 → 同步列表）。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    /** 锁占用标志：既是"已取锁"记录，也是控制 SET NX 结果的开关（预置 true 可模拟"锁被占"）。 */
    private final AtomicBoolean lockTaken = new AtomicBoolean(false);
    private final AtomicInteger dbQueries = new AtomicInteger();
    private CountDownLatch dbEntered;
    private CountDownLatch dbRelease;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        contentDao = mock(ContentDao.class);
        transactionTemplate = mock(TransactionTemplate.class);
        redis = mock(RedisAccess.class);
        jedis = mock(Jedis.class);
        delPipeline = mock(Pipeline.class);
        writePipeline = mock(Pipeline.class);
        stats = new CacheStats();
        conn = mock(Connection.class);
        events.clear();
        lockTaken.set(false);
        dbQueries.set(0);
        dbEntered = null;
        dbRelease = null;

        service = new FeedRebuildService(followDao, contentDao, transactionTemplate, redis, stats);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedRebuildService.class));

        // 第 1 次 pipelined() = 步骤①的 DEL；之后 = 步骤③的分批 ZADD
        when(jedis.pipelined()).thenReturn(delPipeline, writePipeline);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith(CacheKeys.FEED_REBUILD_LOCK_PREFIX)) {
                return lockTaken.compareAndSet(false, true) ? "OK" : null;   // SET NX：未命中 → nil
            }
            events.add("MARKER:" + key);   // 步骤③末尾的完整态标记
            return "OK";
        }).when(jedis).set(anyString(), anyString(), any(SetParams.class));
        doAnswer(inv -> {
            events.add("DEL:" + inv.getArgument(0));
            return null;
        }).when(delPipeline).del(anyString());
        doAnswer(inv -> {
            events.add("ZADD:" + inv.getArgument(0));
            return null;
        }).when(writePipeline).zadd(anyString(), anyDouble(), anyString());

        doAnswer(inv -> ((Function<Jedis, ?>) inv.getArgument(0)).apply(jedis))
                .when(redis).execute(any());
        doAnswer(inv -> {
            ((java.util.function.Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).when(redis).executeVoid(any());

        when(transactionTemplate.execute(any(TransactionTemplate.TransactionAction.class)))
                .thenAnswer(inv -> {
                    events.add(DB);
                    dbQueries.incrementAndGet();
                    if (dbEntered != null) {
                        dbEntered.countDown();
                        dbRelease.await(5, TimeUnit.SECONDS);
                    }
                    TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
                    return action.execute(conn);
                });
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    // ==================== 正常路径 ====================

    @Test
    void rebuildDeletesThenQueriesDbThenZaddsThenWritesMarker() throws SQLException {
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(List.of(42L, 41L)));

        service.rebuildInbox(USER);

        // 三步顺序（红线）：DEL → DB 重查 → ZADD → 写完整态标记
        assertOrder(DEL_INBOX, DEL_MARKER, DB, ZADD, MARKER);
        // ① DEL 同时清掉完整态标记（重建期间"不完整"）
        verify(delPipeline).del(CacheKeys.feedInbox(USER));
        verify(delPipeline).del(CacheKeys.feedInboxFull(USER));
        verify(delPipeline).sync();
        // ② 口径与拉模式同源：复用同一 DAO 方法（is_deleted=0 + ORDER BY create_time DESC, id DESC）
        verify(followDao).getAllFollowedUserIds(conn, USER);
        verify(contentDao).findContentIdsByUsers(conn, List.of(FOLLOWED), 0, PAGE);
        // ③ ZADD（score = member = contentId，与 fanout 同口径）+ 标记
        verify(writePipeline).zadd(CacheKeys.feedInbox(USER), 42L, "42");
        verify(writePipeline).zadd(CacheKeys.feedInbox(USER), 41L, "41");
        verify(writePipeline).expire(CacheKeys.feedInbox(USER), EXPECTED_TTL_SECONDS);
        // 标记 = SET 单命令原子写（value + EX ttl 同批，避免"标记永久存活而收件箱已过期"）
        ArgumentCaptor<SetParams> params = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(eq(CacheKeys.feedInboxFull(USER)),
                eq(CacheKeys.FEED_INBOX_FULL_MARKER_VALUE), params.capture());
        assertEquals(List.of("EX", String.valueOf(EXPECTED_TTL_SECONDS)), paramsOf(params.getValue()));
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void rebuildAcquiresAndReleasesLockWithOwnTokenSetsTtl() {
        stubFollowing(List.of());
        stubPages(List.of(noIds()));

        service.rebuildInbox(USER);

        // 取锁：SET lock token NX EX ttl（NX 原子判重；TTL = 包内常量，仅兜"进程猝死未释放"）
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SetParams> params = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(eq(CacheKeys.feedRebuildLock(USER)), token.capture(), params.capture());
        assertEquals(List.of("NX", "EX", String.valueOf(FeedRebuildService.REBUILD_LOCK_TTL_SECONDS)),
                paramsOf(params.getValue()));
        assertFalse(token.getValue().isEmpty(), "锁值应为持有者 token");
        // 释放：Lua CAS（只删自己的 token，避免误删他人锁）
        ArgumentCaptor<List<String>> args = listCaptor();
        verify(jedis).eval(eq(FeedRebuildService.RELEASE_LOCK_SCRIPT),
                eq(List.of(CacheKeys.feedRebuildLock(USER))), args.capture());
        assertEquals(List.of(token.getValue()), args.getValue());
    }

    @Test
    void rebuildWritesMarkerEvenWhenNothingToStore() {
        // 未关注任何人 = 空但**完整**：仍须写标记，否则二期会把"确实没内容"误判为"收件箱不完整"
        stubFollowing(List.of());
        stubPages(List.of(noIds()));

        service.rebuildInbox(USER);

        assertOrder(DEL_INBOX, DEL_MARKER, DB, MARKER);
        verify(writePipeline, never()).zadd(anyString(), anyDouble(), anyString());
        verify(writePipeline, never()).sync();
        ArgumentCaptor<SetParams> params = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(eq(CacheKeys.feedInboxFull(USER)),
                eq(CacheKeys.FEED_INBOX_FULL_MARKER_VALUE), params.capture());
        assertEquals(List.of("EX", String.valueOf(EXPECTED_TTL_SECONDS)), paramsOf(params.getValue()));
    }

    @Test
    void rebuildPagesThroughAllContentUntilShortPage() throws SQLException {
        List<Long> full = ids(1, PAGE);
        List<Long> tail = ids(PAGE + 1, PAGE + 3);
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(full, tail));

        service.rebuildInbox(USER);

        // 分页口径：不足一页即到底（不依赖 count，防计数漂移）；批间隔 = PAGE
        verify(contentDao).findContentIdsByUsers(conn, List.of(FOLLOWED), 0, PAGE);
        verify(contentDao).findContentIdsByUsers(conn, List.of(FOLLOWED), PAGE, PAGE);
        verify(contentDao, times(2)).findContentIdsByUsers(eq(conn), anyList(), anyInt(), eq(PAGE));
        // 每批一个 pipeline（单连接内多个 pipeline，不逐条往返）
        verify(delPipeline, times(1)).sync();
        verify(writePipeline, times(2)).sync();
        // 末批（3 条 < PAGE）也必须写完
        verify(writePipeline).zadd(CacheKeys.feedInbox(USER), (long) PAGE + 3, String.valueOf(PAGE + 3));
    }

    @Test
    void rebuildStopsAtExactPageBoundary() throws SQLException {
        // 边界：内容数恰为 PAGE 的整数倍 → 靠"下一页空"终止，不得死循环
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(ids(1, PAGE), noIds()));

        service.rebuildInbox(USER);

        verify(contentDao).findContentIdsByUsers(conn, List.of(FOLLOWED), PAGE, PAGE);
        verify(contentDao, times(2)).findContentIdsByUsers(eq(conn), anyList(), anyInt(), eq(PAGE));
        verify(writePipeline, times(1)).sync();
    }

    // ==================== 去重锁 ====================

    @Test
    void rebuildSkipsEverythingWhenLockAlreadyHeld() {
        lockTaken.set(true);   // 已有并发重建持锁 → SET NX 未命中

        service.rebuildInbox(USER);

        verify(redis, never()).executeVoid(any());
        verifyNoInteractions(transactionTemplate);
        verify(jedis, never()).set(eq(CacheKeys.feedInbox(USER)), anyString(), any(SetParams.class));
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
        assertTrue(probe.records().isEmpty(), "锁未获取是预期内的去重结果：不刷 WARNING/SEVERE");
    }

    @Test
    void rebuildSkipsWhenRedisUnavailableForLock() {
        doThrow(new CacheException("redis down")).when(redis).execute(any());

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("Redis 不可用"));
        assertNotNull(warnings.getFirst().getThrown(), "Redis 不可用是该链唯一捕获点 → 必须持栈");
        verify(redis, never()).executeVoid(any());
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void concurrentRebuildsOnlyRebuildOnce() throws Exception {
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(List.of(42L)));
        dbEntered = new CountDownLatch(1);
        dbRelease = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.rebuildInbox(USER);
                    return null;
                }));
            }
            start.countDown();
            assertTrue(dbEntered.await(5, TimeUnit.SECONDS), "胜出的线程应进入 DB 重查");
            dbRelease.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, dbQueries.get(), "并发投递只允许一次实际重建（SET NX 去重）");
        verify(transactionTemplate, times(1)).execute(any());
        verify(writePipeline, times(1)).sync();
    }

    // ==================== 降级路径（裁决：降级吞掉并 ACK，不抛给容器） ====================

    @Test
    void rebuildDegradesOnRedisDeleteFailureAndStillReleasesLock() {
        // 第 1 次 executeVoid（DEL）失败；第 2 次（释放锁）走通 → 恰一条 WARNING
        stubExecuteVoidFailingFirst();
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(List.of(42L)));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("收件箱重建失败"));
        assertNotNull(warnings.getFirst().getThrown(), "Redis 阶段失败是该链唯一捕获点 → 必须持栈");
        // DEL 已失败 → 不查 DB、不写 ZADD；打点归 FEED 域
        verifyNoInteractions(transactionTemplate);
        verify(writePipeline, never()).sync();
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
        // 失败也要释放锁（finally），不靠 TTL 兜底
        verify(redis, times(2)).executeVoid(any());
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void rebuildDegradesOnRedisWriteFailureWithoutWritingMarker() {
        // 第 1 次（DEL）走通、第 2 次（ZADD）失败、第 3 次（释放锁）走通
        stubExecuteVoidFailingSecond();
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(List.of(42L)));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("收件箱重建失败"));
        assertNotNull(warnings.getFirst().getThrown(), "Redis 写失败是该链唯一捕获点 → 必须持栈");
        // 标记必须在全部 ZADD 之后写：写失败 ⇒ 不得留下"完整态"假标记（二期据此回退拉模式）
        verify(jedis, never()).set(eq(CacheKeys.feedInboxFull(USER)), anyString(), any(SetParams.class));
        verify(writePipeline, never()).sync();
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void rebuildDegradesOnDbFailureWithExactlyOneStackedRecord() throws SQLException {
        stubFollowing(List.of(FOLLOWED));
        when(contentDao.findContentIdsByUsers(eq(conn), anyList(), anyInt(), eq(PAGE)))
                .thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        // 源头（事务回调）持 SEVERE + 栈；本类只补结论行、不带栈 ⇒ 一次失败恰一条带堆栈记录
        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建 DB 重查失败"));
        assertNotNull(severes.getFirst().getThrown(), "DB 失败源头必须持栈");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条结论行，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("DB 重查失败"));
        assertNull(warnings.getFirst().getThrown(), "结论行不带栈（源头已持栈）");
        // DEL 已发生（先清后建是红线顺序），但无标记 ⇒ 收件箱处于"不完整"态（安全降级）
        verify(jedis, never()).set(eq(CacheKeys.feedInboxFull(USER)), anyString(), any(SetParams.class));
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL),
                "DB 失败不是缓存写失败，不套 WRITE_FAIL 打点");
    }

    @Test
    void rebuildSwallowsUnexpectedRuntimeFailure() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, USER)).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建异常"));
        assertNotNull(severes.getFirst().getThrown(), "需人介入 → SEVERE + 栈");
    }

    @Test
    void rebuildLockReleaseFailureIsSwallowedWithoutStack() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new CacheException("redis down"));
        stubFollowing(List.of());
        stubPages(List.of(noIds()));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        // 释放失败不改变结果（TTL 兜底）→ WARNING 不带栈（避免"一次失败两条带堆栈记录"）
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("锁释放失败"));
        assertNull(warnings.getFirst().getThrown(), "非影响性失败（TTL 兜底）→ 不带栈");
        assertTrue(probe.stackedRecords().isEmpty(), "本链无源头失败 → 不应有带堆栈记录");
    }

    /** 契约兜底：释放锁抛**非 CacheException** 的运行时异常也不得逃出去（"绝不抛"要如实成立）。 */
    @Test
    void rebuildSwallowsUnexpectedReleaseFailure() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new IllegalStateException("boom"));
        stubFollowing(List.of());
        stubPages(List.of(noIds()));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        assertEquals(1, probe.atLevel(Level.WARNING).size());
        assertTrue(probe.stackedRecords().isEmpty(), "非影响性失败，不额外持栈");
    }

    /** 契约兜底：取锁阶段抛非 CacheException（RedisAccess 还有 IAE 等非 Cache 出口）也必须降级不抛。 */
    @Test
    void rebuildSkipsWhenLockAcquireThrowsUnexpectedError() {
        doThrow(new IllegalStateException("boom")).when(redis).execute(any());

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("取锁异常"));
        assertNotNull(severes.getFirst().getThrown(), "意外异常需人介入 → SEVERE + 栈");
        verify(redis, never()).executeVoid(any());
        verifyNoInteractions(transactionTemplate);
    }

    // ==================== 域边界 ====================

    @Test
    void rebuildTouchesOnlyFeedDomainKeys() {
        stubFollowing(List.of(FOLLOWED));
        stubPages(List.of(List.of(42L, 41L)));

        service.rebuildInbox(USER);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(delPipeline, times(2)).del(keys.capture());
        verify(writePipeline, times(2)).zadd(keys.capture(), anyDouble(), anyString());
        verify(writePipeline).expire(keys.capture(), anyLong());
        verify(jedis, times(2)).set(keys.capture(), anyString(), any(SetParams.class));
        assertEquals(7, keys.getAllValues().size(), "2 DEL + 2 ZADD + 1 EXPIRE + 2 SET（锁 + 标记）");
        for (String key : keys.getAllValues()) {
            assertTrue(key.startsWith("feed:"), "越界 key: " + key);
        }
    }

    // ==================== 辅助 ====================

    private void stubFollowing(List<Long> followedIds) {
        try {
            when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(followedIds);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 连续多页：不足一页即到底（最后一页应 < PAGE）。 */
    private void stubPages(List<List<Long>> pages) {
        try {
            org.mockito.stubbing.OngoingStubbing<List<Long>> stub =
                    when(contentDao.findContentIdsByUsers(eq(conn), anyList(), anyInt(), eq(PAGE)));
            for (List<Long> page : pages) {
                stub = stub.thenReturn(page);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** executeVoid 第 1 次调用失败（DEL），之后走通（释放锁）。 */
    @SuppressWarnings("unchecked")
    private void stubExecuteVoidFailingFirst() {
        doThrow(new CacheException("redis down")).doAnswer(inv -> {
            ((java.util.function.Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).when(redis).executeVoid(any());
    }

    /** executeVoid 第 2 次调用失败（ZADD），第 1 次（DEL）与第 3 次（释放锁）走通。 */
    @SuppressWarnings("unchecked")
    private void stubExecuteVoidFailingSecond() {
        doAnswer(inv -> {
            ((java.util.function.Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).doThrow(new CacheException("redis down")).doAnswer(inv -> {
            ((java.util.function.Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).when(redis).executeVoid(any());
    }

    private void assertOrder(String... markers) {
        List<String> snapshot = new ArrayList<>(events);
        int previous = -1;
        for (String marker : markers) {
            int index = snapshot.indexOf(marker);
            assertTrue(index >= 0, () -> "缺少交互: " + marker + "，实际: " + snapshot);
            assertTrue(index > previous, () -> "顺序不符（应为 " + String.join(" → ", markers)
                    + "），实际: " + snapshot);
            previous = index;
        }
    }

    /**
     * 把 {@link SetParams} 还原成 Redis 命令**参数**（走 Jedis 自带的序列化路径）——{@code SetParams}
     * 未实现 {@code equals}，故不能直接字面量比对；这样才能断言 {@code NX} / {@code EX ttl} 口径。
     * 返回时跳过首元素（{@code CommandArguments} 的第一项是命令名 {@code SET} 本身）。
     */
    private static List<String> paramsOf(SetParams params) {
        CommandArguments args = new CommandArguments(Protocol.Command.SET);
        params.addParams(args);
        List<String> out = new ArrayList<>();
        boolean first = true;
        for (Rawable rawable : args) {
            if (first) {
                first = false;
                continue;
            }
            out.add(new String(rawable.getRaw(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** 空页（显式类型，避免 {@code List.of()} 嵌套泛型推断歧义）。 */
    private static List<Long> noIds() {
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
