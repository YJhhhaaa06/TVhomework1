package com.itheima.feed.service;

import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.dao.FollowDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Jedis;
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
import java.util.function.Consumer;
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
 * {@link FeedRebuildService} 单测（feed2-22 T22；由 feed1-19 T19 的三步重建单测改写）：
 * 窗口重算（每作者最近 K → 归并去重降序 → 裁剪 C）/ 大V排除 / 单事务落库 + 同步状态 /
 * **提交后**缓存失效 / 去重锁 / 三类降级 / 只碰 FEED 域 key / 并发仅一次实际重建。
 *
 * <p>隔离手法：mock {@link FollowDao} / {@link ContentDao} / {@link FeedInboxDao} /
 * {@link FeedBigVRouter} / {@link TransactionTemplate}（回调打到 mock {@link Connection}）/
 * {@link RedisAccess}（回调打到 mock {@link Jedis}），**不依赖真实 Redis / DB / broker**；
 * {@link CacheStats} 用真实件以断言"FEED 域可辨"。交互序列记入 {@code events}，用于断言
 * **"先 DB 真相（清 → 窗口 → 写 → 同步状态）、后缓存失效"** 的顺序红线。
 *
 * <p>注：两个 DB 相关 stub（关注集读取 / 窗口重查）**只在测试内**通过 {@code when(...)} 安装，
 * 以免 setUp 期的一次 {@code when} 调用把交互事件提前写进 {@code events}（顺序断言会因此失真）。
 */
class FeedRebuildServiceTest {

    private static final long USER = 7L;
    private static final long AUTHOR_A = 8L;
    private static final long AUTHOR_B = 9L;
    private static final long AUTHOR_C = 10L;

    /** 窗口参数取自 app.properties（AppConfig 带默认值读取）：K=20 / C=200。 */
    private static final int K = AppConfig.getFeedInboxWindowPerAuthor();
    private static final int C = AppConfig.getFeedInboxWindowMax();
    private static final int AUTHOR_BATCH = FeedRebuildService.AUTHOR_BATCH;
    private static final int THREADS = 16;

    private static final String FOLLOW = "FOLLOW";
    private static final String DELETE = "DELETE";
    private static final String WINDOW = "WINDOW";
    private static final String SYNC = "SYNC";

    private FollowDao followDao;
    private ContentDao contentDao;
    private TransactionTemplate transactionTemplate;
    private FeedInboxDao feedInboxDao;
    private FeedBigVRouter bigVRouter;
    private RedisAccess redis;
    private Jedis jedis;
    private Connection conn;
    private CacheStats stats;
    private FeedRebuildService service;
    private LogProbe probe;

    /** 交互序列（断言顺序用；并发用例下多线程写 → 同步列表）。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    /** 锁占用标志：既是"已取锁"记录，也是控制 SET NX 结果的开关（预置 true 可模拟"锁被占"）。 */
    private final AtomicBoolean lockTaken = new AtomicBoolean(false);
    private CountDownLatch dbEntered;
    private CountDownLatch dbRelease;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        contentDao = mock(ContentDao.class);
        transactionTemplate = mock(TransactionTemplate.class);
        feedInboxDao = mock(FeedInboxDao.class);
        bigVRouter = mock(FeedBigVRouter.class);
        redis = mock(RedisAccess.class);
        jedis = mock(Jedis.class);
        conn = mock(Connection.class);
        stats = new CacheStats();
        events.clear();
        lockTaken.set(false);
        dbEntered = null;
        dbRelease = null;

        service = new FeedRebuildService(followDao, contentDao, transactionTemplate, feedInboxDao,
                bigVRouter, redis, stats);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedRebuildService.class));

        // 锁 SET NX：未命中返回 nil（Jedis 语义：null）
        doAnswer(inv -> lockTaken.compareAndSet(false, true) ? "OK" : null)
                .when(jedis).set(anyString(), anyString(), any(SetParams.class));
        // 缓存失效（DEL 多键）：只记事件名——键集合由 capturedInvalidatedKeys() 以 captor 断言
        // （varargs 的 getArgument(0) 语义随 Mockito 版本而异，captor 是既有先例的稳妥路径）
        doAnswer(inv -> {
            events.add("DEL");
            return 0L;
        }).when(jedis).del(any(String[].class));
        // 窗口替换的 DB 原子动作（顺序断言的三个标记）
        doAnswer(inv -> {
            events.add(DELETE);
            return 0;
        }).when(feedInboxDao).deleteByUser(conn, USER);
        doAnswer(inv -> {
            events.add("INSERT");
            return ((List<?>) inv.getArgument(2)).size();
        }).when(feedInboxDao).insertBatch(eq(conn), eq(USER), anyList());
        doAnswer(inv -> {
            events.add(SYNC);
            return null;
        }).when(feedInboxDao).upsertSync(conn, USER);

        doAnswer(inv -> ((Function<Jedis, ?>) inv.getArgument(0)).apply(jedis))
                .when(redis).execute(any());
        doAnswer(inv -> {
            ((Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).when(redis).executeVoid(any());

        when(transactionTemplate.execute(any(TransactionTemplate.TransactionAction.class)))
                .thenAnswer(inv -> {
                    events.add("TX");
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
    void rebuildReplacesWindowAndWritesSyncThenInvalidatesCache() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        stubWindow(List.of(42L, 41L));

        service.rebuildInbox(USER);

        // 顺序红线：读关注集 → [DB 清 → 窗口重查 → 窗口写 → 同步状态] → 提交后失效缓存。
        // 用**全序列相等**（而非子序列）锁定两个核心不变量：① 窗口替换是**单事务**（DELETE→SYNC
        // 之间不得再出现 TX）；② 缓存失效严格发生在同步状态之后（提交后失效）。
        assertExactEvents("TX", FOLLOW, "TX", DELETE, WINDOW, "INSERT", SYNC, "DEL");
        verify(feedInboxDao).deleteByUser(conn, USER);
        verify(contentDao).findRecentContentIdsByUsers(conn, List.of(AUTHOR_A), K);
        verify(feedInboxDao).insertBatch(conn, USER, List.of(42L, 41L));
        verify(feedInboxDao).upsertSync(conn, USER);
        assertInvalidatedKeys(USER);
        // 重建不再写 Redis（无 ZADD、无 SET 标记）：非影响性
        verify(jedis, never()).zadd(anyString(), anyDouble(), anyString());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void rebuildAcquiresAndReleasesLockWithOwnTokenSetsTtl() throws SQLException {
        stubFollowing(List.of());

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
    void rebuildTrimsWindowToMaxC() throws SQLException {
        // 单作者返回远超 C 条 ⇒ 必须裁剪到 C，且为 contentId 降序（取最大的 C 个）
        int total = C + 50;
        List<Long> huge = ascending(1, total);
        Collections.shuffle(huge);
        stubFollowing(List.of(AUTHOR_A));
        stubWindow(huge);

        service.rebuildInbox(USER);

        assertEquals(descending(total - C + 1, total), capturedWindow(),
                "窗口应为 contentId 降序的前 " + C + " 条（保留最大的 C 个）");
    }

    @Test
    void rebuildKeepsPerAuthorTopKAndBatchesAuthors() throws SQLException {
        // 作者数 > AUTHOR_BATCH ⇒ 按批切分调用（每批一条 UNION ALL 语句），每批 limit 均为 K
        List<Long> authors = ascending(100, 99 + AUTHOR_BATCH * 2 + 5);   // 105 个作者
        stubFollowing(authors);
        stubWindow(List.of());

        service.rebuildInbox(USER);

        ArgumentCaptor<List<Long>> authorBatches = listCaptor();
        verify(contentDao, times(3)).findRecentContentIdsByUsers(eq(conn), authorBatches.capture(), eq(K));
        List<List<Long>> batches = authorBatches.getAllValues();
        assertEquals(AUTHOR_BATCH, batches.get(0).size());
        assertEquals(AUTHOR_BATCH, batches.get(1).size());
        assertEquals(5, batches.get(2).size());
        assertEquals(authors, concat(batches), "作者列表被完整切分、无重复无遗漏");
    }

    @Test
    void rebuildExcludesBigVAuthorsBeforeWindowQuery() throws SQLException {
        stubFollowing(List.of(AUTHOR_A, AUTHOR_B, AUTHOR_C));
        when(bigVRouter.isBigV(AUTHOR_B)).thenReturn(true);
        stubWindow(List.of(42L));

        service.rebuildInbox(USER);

        // 判定逐作者各一次（判定单点），且大V作者**不进窗口查询入参**（避免与"大V发件箱"重复）
        verify(bigVRouter).isBigV(AUTHOR_A);
        verify(bigVRouter).isBigV(AUTHOR_B);
        verify(bigVRouter).isBigV(AUTHOR_C);
        verify(contentDao).findRecentContentIdsByUsers(conn, List.of(AUTHOR_A, AUTHOR_C), K);
        verify(contentDao, times(1)).findRecentContentIdsByUsers(eq(conn), anyList(), eq(K));
        assertEquals(List.of(42L), capturedWindow(), "窗口只由非大V作者的产物构成");
    }

    @Test
    void rebuildWritesSyncEvenWhenWindowEmpty() throws SQLException {
        // 未关注任何人 = 空但**已同步**：仍须清 + 写同步状态，否则二期会把"确实没内容"当作"未同步"而回退拉模式
        stubFollowing(List.of());

        service.rebuildInbox(USER);

        assertExactEvents("TX", FOLLOW, "TX", DELETE, SYNC, "DEL");
        verify(contentDao, never()).findRecentContentIdsByUsers(any(), anyList(), anyInt());
        verify(feedInboxDao, never()).insertBatch(any(), anyLong(), anyList());
        verify(feedInboxDao).upsertSync(conn, USER);
    }

    @Test
    void rebuildWritesSyncWhenAllAuthorsAreBigV() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        when(bigVRouter.isBigV(AUTHOR_A)).thenReturn(true);

        service.rebuildInbox(USER);

        assertExactEvents("TX", FOLLOW, "TX", DELETE, SYNC, "DEL");
        verify(contentDao, never()).findRecentContentIdsByUsers(any(), anyList(), anyInt());
        verify(feedInboxDao, never()).insertBatch(any(), anyLong(), anyList());
        verify(feedInboxDao).upsertSync(conn, USER);
    }

    @Test
    void rebuildMergeDedupsAndSortsDescending() throws SQLException {
        // 重复 + 乱序：归并去重 → contentId 降序
        stubFollowing(List.of(AUTHOR_A, AUTHOR_B));
        stubWindow(List.of(41L, 42L, 42L, 43L, 41L));

        service.rebuildInbox(USER);

        assertEquals(List.of(43L, 42L, 41L), capturedWindow());
    }

    // ==================== 去重锁 ====================

    @Test
    void rebuildSkipsEverythingWhenLockAlreadyHeld() {
        lockTaken.set(true);   // 已有并发重建持锁 → SET NX 未命中

        service.rebuildInbox(USER);

        verify(redis, never()).executeVoid(any());
        verifyNoInteractions(transactionTemplate);
        verifyNoInteractions(feedInboxDao);
        verify(jedis, never()).del(any(String[].class));
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
        stubFollowing(List.of(AUTHOR_A));
        stubWindow(List.of(42L));
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
            assertTrue(dbEntered.await(5, TimeUnit.SECONDS), "胜出的线程应进入 DB 阶段");
            dbRelease.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 并发投递只允许一次实际重建（SET NX 去重）：关注集读取与窗口替换各一次
        verify(followDao, times(1)).getAllFollowedUserIds(any(), eq(USER));
        verify(feedInboxDao, times(1)).deleteByUser(conn, USER);
        verify(transactionTemplate, times(2)).execute(any());
    }

    // ==================== 降级路径（裁决：降级吞掉并 ACK，不抛给容器） ====================

    @Test
    void rebuildDegradesOnCacheInvalidateFailureAfterCommit() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        stubWindow(List.of(42L));
        // 只有第 1 次 executeVoid（提交后的缓存失效）失败；第 2 次（释放锁）走通 ⇒ 恰一条 WARNING
        stubExecuteVoidFailingFirst();

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("缓存失效失败"));
        assertNotNull(warnings.getFirst().getThrown(), "缓存失效是该链唯一 Redis 捕获点 → 必须持栈");
        // DB 已提交（窗口 + 同步状态）→ 失效失败只是纯降级，仍需释放锁
        verify(feedInboxDao).upsertSync(conn, USER);
        verify(jedis).eval(anyString(), anyList(), anyList());
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void rebuildDegradesOnDbFailureWithExactlyOneStackedRecord() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        when(contentDao.findRecentContentIdsByUsers(eq(conn), anyList(), anyInt()))
                .thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        // 源头（事务回调）持 SEVERE + 栈；本类只补结论行、不带栈 ⇒ 一次失败恰一条带堆栈记录
        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("DB 窗口重算失败"));
        assertNotNull(severes.getFirst().getThrown(), "DB 失败源头必须持栈");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条结论行，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("DB 窗口重算失败"));
        assertNull(warnings.getFirst().getThrown(), "结论行不带栈（源头已持栈）");
        // 事务回滚 ⇒ 窗口与同步状态一并回滚 ⇒ 不得写同步、不得失效缓存，但失败也要释放锁
        verify(feedInboxDao, never()).upsertSync(any(), anyLong());
        verify(jedis, never()).del(any(String[].class));
        verify(jedis).eval(anyString(), anyList(), anyList());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL),
                "DB 失败不是缓存写失败，不套 WRITE_FAIL 打点");
    }

    @Test
    void rebuildDegradesWhenFollowListQueryFails() throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, USER)).thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("关注列表查询失败"));
        assertNotNull(severes.getFirst().getThrown(), "DB 失败源头必须持栈");
        // 关注集都没读到 ⇒ 不得进入窗口替换事务（只发生了 1 个事务），也不失效缓存
        verify(transactionTemplate, times(1)).execute(any());
        verifyNoInteractions(feedInboxDao);
        verify(jedis, never()).del(any(String[].class));
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void rebuildSwallowsUnexpectedRuntimeFailure() throws SQLException {
        doAnswer(inv -> {
            throw new IllegalStateException("boom");
        }).when(feedInboxDao).deleteByUser(conn, USER);

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建异常"));
        assertNotNull(severes.getFirst().getThrown(), "需人介入 → SEVERE + 栈");
    }

    /** 配置非法（大V名单）会从判定单点抛 IllegalArgumentException —— 必须被兜底、不得穿透消费容器。 */
    @Test
    void rebuildSwallowsBadBigVConfig() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        when(bigVRouter.isBigV(anyLong()))
                .thenThrow(new IllegalArgumentException("feed.bigv.userIds 含非法用户 id"));

        assertDoesNotThrow(() -> service.rebuildInbox(USER));

        List<LogRecord> severes = probe.atLevel(Level.SEVERE);
        assertEquals(1, severes.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severes.getFirst().getMessage().contains("收件箱重建异常"));
        verify(transactionTemplate, times(1)).execute(any());   // 只读到关注集，未进入窗口替换
        verify(jedis, never()).del(any(String[].class));
    }

    @Test
    void rebuildLockReleaseFailureIsSwallowedWithoutStack() throws SQLException {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new CacheException("redis down"));
        stubFollowing(List.of());

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
    void rebuildSwallowsUnexpectedReleaseFailure() throws SQLException {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new IllegalStateException("boom"));
        stubFollowing(List.of());

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
    void rebuildTouchesOnlyFeedDomainKeys() throws SQLException {
        stubFollowing(List.of(AUTHOR_A));
        stubWindow(List.of(42L, 41L));

        service.rebuildInbox(USER);

        // 锁 SET key（T22 起重建不再写任何收件箱 key，故 set 只发生一次 = 锁）
        ArgumentCaptor<String> setKeys = ArgumentCaptor.forClass(String.class);
        verify(jedis).set(setKeys.capture(), anyString(), any(SetParams.class));
        assertTrue(setKeys.getValue().startsWith("feed:"), "越界 key: " + setKeys.getValue());
        // 失效三件套（外层 empty:/partial: 标记先剥到数据 key，再验 feed: 命名空间）
        List<String> invalidated = capturedInvalidatedKeys();
        assertEquals(3, invalidated.size());
        for (String key : invalidated) {
            String dataKey = key.startsWith("empty:") ? key.substring("empty:".length())
                    : key.startsWith("partial:") ? key.substring("partial:".length()) : key;
            assertTrue(dataKey.startsWith("feed:"), "越界 key: " + key);
        }
    }

    // ==================== 辅助 ====================

    private void stubFollowing(List<Long> followedIds) throws SQLException {
        when(followDao.getAllFollowedUserIds(conn, USER)).thenAnswer(inv -> {
            events.add(FOLLOW);
            return new ArrayList<>(followedIds);
        });
    }

    /** 窗口重查结果（连续调用按序返回；作者数 ≤ AUTHOR_BATCH 时只消耗一个）。 */
    private void stubWindow(List<Long> contentIds) throws SQLException {
        stubWindows(List.of(contentIds));
    }

    /** 多批窗口重查结果（按调用次序逐个返回；用于跨批归并用例）。 */
    private void stubWindows(List<List<Long>> batches) throws SQLException {
        OngoingStubbing<List<Long>> stub =
                when(contentDao.findRecentContentIdsByUsers(eq(conn), anyList(), anyInt()));
        for (List<Long> batch : batches) {
            stub = stub.thenAnswer(inv -> {
                events.add(WINDOW);
                return new ArrayList<>(batch);
            });
        }
    }

    /**
     * executeVoid 第 1 次调用失败（提交后的缓存失效），之后走通（释放锁）——用于把
     * "缓存失效失败"与"锁释放失败"两类降级分开断言（一次失败恰一条带堆栈记录）。
     */
    @SuppressWarnings("unchecked")
    private void stubExecuteVoidFailingFirst() {
        doThrow(new CacheException("redis down")).doAnswer(inv -> {
            ((Consumer<Jedis>) inv.getArgument(0)).accept(jedis);
            return null;
        }).when(redis).executeVoid(any());
    }

    private List<Long> capturedWindow() throws SQLException {
        ArgumentCaptor<List<Long>> captor = listCaptor();
        verify(feedInboxDao).insertBatch(eq(conn), eq(USER), captor.capture());
        return captor.getValue();
    }

    private List<String> capturedInvalidatedKeys() {
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(jedis).del(captor.capture());
        return List.of(captor.getValue());
    }

    private void assertInvalidatedKeys(long userId) {
        assertEquals(List.of(CacheKeys.feedInboxCacheKeys(userId)), capturedInvalidatedKeys(),
                "失效集应为三件套（数据 key + empty: + partial:）");
    }

    /**
     * 事件序列**全等**断言（不是子序列）：既锁定顺序，也锁定"窗口替换只有一个事务"
     * （`DELETE → SYNC` 之间不得再出现 `TX`）与"缓存失效严格在同步状态之后"。
     */
    private void assertExactEvents(String... expected) {
        assertEquals(List.of(expected), new ArrayList<>(events),
                "事件序列应为「读关注集 → [清 → 窗口 → 写 → 同步] → 失效缓存」");
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
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static List<Long> concat(List<List<Long>> lists) {
        List<Long> all = new ArrayList<>();
        for (List<Long> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    /** [from, to] 升序。 */
    private static List<Long> ascending(int from, int to) {
        List<Long> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add((long) i);
        }
        return list;
    }

    /** [from, to] 降序（to → from）。 */
    private static List<Long> descending(int from, int to) {
        List<Long> list = new ArrayList<>();
        for (int i = to; i >= from; i--) {
            list.add((long) i);
        }
        return list;
    }
}
