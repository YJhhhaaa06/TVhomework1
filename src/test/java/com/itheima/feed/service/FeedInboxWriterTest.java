package com.itheima.feed.service;

import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.dao.FollowDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.OngoingStubbing;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedInboxWriter} 单测（feed2-21 T21；由 feed1-18 T18 的 FeedInboxCacheTest 改写；
 * feed2-23 T23 增"大V发件箱写后失效"；**feed3-T30 改写粉丝遍历隔离手法**：mock
 * {@link FollowDao} 的 keyset 游标读（原 mock {@code FollowCache.getFollowerWindow} 的 offset 窗口）；
 * **feed3-T32 改写失败面口径**：DB 类失败（游标读 / 落库 / 意外异常）由"吞掉"改为**抛出**
 * （交消费容器有限重试），仅缓存失效 DEL 失败仍降级吞掉）：
 * 游标迭代 / DB 真相批量落库 / 写后失效 DEL（outbox + 收件箱）/ 大V跳过 / 失败面 / FEED 域打点。
 *
 * <p>隔离手法：mock {@link FollowDao}（游标直读）/ {@link FeedInboxDao}（落库）/
 * {@link TransactionTemplate}（回调打到 mock {@link Connection}）/ {@link FeedBigVRouter}（大V判定）/
 * {@link RedisAccess}（回调打到 mock {@link Jedis}），**不依赖真实 Redis / DB / broker**；
 * {@link CacheStats} 用真实件，以便断言"FEED 域可辨"。交互序列记入 {@code events}（读 + 写各记一条
 * "DB"），用于断言"outbox 失效最前 → 游标读 → DB 真相 → 收件箱失效"的顺序红线；游标语义
 * （严格递增、以首页末位 id 续游标而非 offset）由逐次 {@code verify} 的**精确实参**证。
 */
class FeedInboxWriterTest {

    private static final long AUTHOR = 9L;
    private static final long CONTENT = 42L;
    private static final int BATCH = AppConfig.getFeedFanoutBatch();
    /** 补推"作者最近 K 条"的 K（= 重建每作者窗口口径，默认 20）。 */
    private static final int K = AppConfig.getFeedInboxWindowPerAuthor();

    private FollowDao followDao;
    private FeedInboxDao feedInboxDao;
    private ContentDao contentDao;
    private TransactionTemplate transactionTemplate;
    private FeedBigVRouter bigVRouter;
    private RedisAccess redis;
    private Jedis jedis;
    private Connection conn;
    private CacheStats stats;
    private FeedInboxWriter writer;
    private LogProbe probe;

    /** 交互序列（断言"outbox 失效最前 → 游标读 → DB 真相 → 收件箱失效"的顺序红线）。 */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        followDao = mock(FollowDao.class);
        feedInboxDao = mock(FeedInboxDao.class);
        contentDao = mock(ContentDao.class);
        transactionTemplate = mock(TransactionTemplate.class);
        bigVRouter = mock(FeedBigVRouter.class);
        redis = mock(RedisAccess.class);
        jedis = mock(Jedis.class);
        conn = mock(Connection.class);
        stats = new CacheStats();
        events.clear();

        writer = new FeedInboxWriter(followDao, feedInboxDao, contentDao, transactionTemplate,
                bigVRouter, redis, stats);
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
    void fanoutInvalidatesOutboxThenReadsCursorThenWritesDbThenInvalidatesInbox() throws SQLException {
        stubPages(List.of(List.of(11L, 12L)));

        writer.fanout(CONTENT, AUTHOR);

        // 顺序红线（跨 mock InOrder）：outbox 失效最前 → 游标读 → DB 真相落库 → 收件箱失效
        InOrder inOrder = inOrder(jedis, followDao, feedInboxDao);
        inOrder.verify(jedis).del(CacheKeys.feedOutbox(AUTHOR), CacheKeys.empty(CacheKeys.feedOutbox(AUTHOR)));
        inOrder.verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 0L, BATCH);
        inOrder.verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, List.of(11L, 12L));
        inOrder.verify(jedis).del(CacheKeys.feedInbox(11L), CacheKeys.empty(CacheKeys.feedInbox(11L)),
                CacheKeys.partial(CacheKeys.feedInbox(11L)),
                CacheKeys.feedInbox(12L), CacheKeys.empty(CacheKeys.feedInbox(12L)),
                CacheKeys.partial(CacheKeys.feedInbox(12L)));
        // 收件箱写后失效：一次 DEL 多键（每粉丝 3 键 = 三件套；完整态标记已随 T22 退役）
        assertEquals(List.of("DEL", "DB", "DB", "DEL"), events);
        // 一批粉丝 = 读 / 写各一次 DB 连接 + 一次 Redis 连接（outbox）+ 一次 Redis 连接（收件箱）
        verify(transactionTemplate, times(2)).execute(any());
        verify(redis, times(2)).executeVoid(any());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutIteratesAllPagesUntilShortPageByKeysetCursor() throws SQLException {
        // 页内 id 带整体偏移（首页末位 = 10+BATCH）：游标必须取"末位 id"而非"已处理行数（offset）"
        List<Long> first = ids(11, 10 + BATCH);
        List<Long> second = ids(20 + BATCH, 19 + 2 * BATCH);
        List<Long> third = ids(30 + 2 * BATCH, 30 + 2 * BATCH + 39);
        stubPages(List.of(first, second, third));

        writer.fanout(CONTENT, AUTHOR);

        // keyset 游标严格递增（0 → 首页末位 → 次页末位），times(3)
        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 0L, BATCH);
        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 10L + BATCH, BATCH);
        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 19L + 2 * BATCH, BATCH);
        verify(followDao, times(3)).getFollowerUserIdsAfter(eq(conn), eq(AUTHOR), anyLong(), eq(BATCH));
        // 每批一条落库 + 一次失效；末批（40 条 < BATCH）也必须写完
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, third);
        verify(transactionTemplate, times(6)).execute(any());   // 3 读 + 3 写
        // 1 次 outbox 失效 + 3 次收件箱失效
        verify(redis, times(4)).executeVoid(any());
        assertEquals(List.of("DEL",
                "DB", "DB", "DEL",
                "DB", "DB", "DEL",
                "DB", "DB", "DEL"), events);
    }

    @Test
    void fanoutStopsWhenFirstPageEmpty() throws SQLException {
        stubPages(List.of(emptyPage()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followDao, times(1)).getFollowerUserIdsAfter(eq(conn), eq(AUTHOR), anyLong(), anyInt());
        verify(feedInboxDao, never()).insertIgnoreBatch(any(), anyLong(), any());
        verify(transactionTemplate, times(1)).execute(any());   // 仅一次游标读（无落库）
        // 页为空 ⇒ 无粉丝可失效；但 outbox 失效**照旧执行**（发表者本人的发件箱要先失效）
        verify(jedis, times(1)).del(CacheKeys.feedOutboxCacheKeys(AUTHOR));
        verify(redis, times(1)).executeVoid(any());
    }

    @Test
    void fanoutStopsAfterExactBatchFollowedByEmptyPage() throws SQLException {
        // 边界：粉丝数恰为 BATCH 的整数倍 —— 靠"下一窗空"终止，不得死循环
        stubPages(List.of(ids(1, BATCH), List.of()));

        writer.fanout(CONTENT, AUTHOR);

        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, BATCH, BATCH);
        verify(followDao, times(2)).getFollowerUserIdsAfter(eq(conn), eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, times(3)).execute(any());   // 2 读 + 1 写
        verify(redis, times(2)).executeVoid(any());   // outbox + 首批收件箱
    }

    @Test
    void fanoutTouchesOnlyFeedKeys() throws SQLException {
        // 红线：不改读路径 —— 只碰 feed:outbox:* / feed:inbox:* 及其派生标记（empty:/partial:），
        //       不写 content:* / user:*
        stubPages(List.of(List.of(11L, 12L, 13L)));

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
    void fanoutInvalidatesOutboxBeforeBigVEarlyReturn() throws SQLException {
        when(bigVRouter.isBigV(AUTHOR)).thenReturn(true);
        // 防御：即使游标可返回，也不得被读取（大V在游标迭代之前就返回）
        stubPages(List.of(List.of(11L)));

        writer.fanout(CONTENT, AUTHOR);

        verify(bigVRouter).isBigV(AUTHOR);
        // T23 核心：大V发布**必须**失效自己的发件箱（这正是读侧唯一会读它的场景）
        verify(jedis, times(1)).del(CacheKeys.feedOutboxCacheKeys(AUTHOR));
        assertEquals(List.of("DEL"), events);
        verify(followDao, never()).getFollowerUserIdsAfter(any(), anyLong(), anyLong(), anyInt());
        verify(transactionTemplate, never()).execute(any());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "大V跳过属常态路由，不应记 WARNING");
    }

    // ==================== 失败面（feed3-T32：DB 类抛出 / 缓存失败降级） ====================

    @Test
    void fanoutThrowsOnFollowerCursorFailureWithSingleStackedSourceLog() throws SQLException {
        // feed3-T32：游标读失败不再降级吞掉——抛出交消费容器有限重试（耗尽转死信）
        when(followDao.getFollowerUserIdsAfter(any(), eq(AUTHOR), anyLong(), anyInt()))
                .thenThrow(new SQLException("db down"));

        ServerException thrown = assertThrows(ServerException.class, () -> writer.fanout(CONTENT, AUTHOR));

        assertTrue(thrown.getMessage().contains("写扩散粉丝游标查询失败"), "异常应可定位失败阶段");
        // 源头（游标查询回调）持 SEVERE + 栈恰一条；结论行 WARNING 恰一条、不带栈
        List<LogRecord> severe = probe.atLevel(Level.SEVERE);
        assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severe.getFirst().getMessage().contains("写扩散粉丝游标查询失败"));
        assertNotNull(severe.getFirst().getThrown(), "游标读失败是该链唯一捕获点 → 持栈");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("交消费重试"), "结论行应表明交容器重试");
        assertNull(warnings.getFirst().getThrown(), "源头已持栈 → 此处为结论行、不带栈");
        verify(transactionTemplate, times(1)).execute(any());   // 仅一次失败的游标读
        verify(feedInboxDao, never()).insertIgnoreBatch(any(), anyLong(), any());
        verify(redis, times(1)).executeVoid(any());   // 仅 outbox 失效（成功，无日志）
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutThrowsOnDbWriteFailure() throws SQLException {
        // feed3-T32：落库失败不再"短路 ACK"——抛出后循环即止，交消费容器重试（幂等重放）
        stubPages(List.of(ids(1, BATCH), ids(BATCH + 1, BATCH + 10)));
        when(feedInboxDao.insertIgnoreBatch(eq(conn), eq(CONTENT), any()))
                .thenThrow(new SQLException("db down"));

        ServerException thrown = assertThrows(ServerException.class, () -> writer.fanout(CONTENT, AUTHOR));

        assertTrue(thrown.getMessage().contains("写扩散收件箱落库失败"));
        verify(followDao, times(1)).getFollowerUserIdsAfter(eq(conn), eq(AUTHOR), anyLong(), anyInt());
        verify(transactionTemplate, times(2)).execute(any());   // 1 读 + 1 失败写（抛出即止，不再续读）
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
        stubPages(List.of(first, second));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(transactionTemplate, times(4)).execute(any());   // 2 读 + 2 写
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
        stubPages(List.of(first, second));
        doAnswer(inv -> {
            events.add("DEL");
            Consumer<Jedis> action = inv.getArgument(0);
            action.accept(jedis);
            return null;
        }).doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.fanout(CONTENT, AUTHOR));

        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, CONTENT, second);
        verify(transactionTemplate, times(4)).execute(any());   // 2 读 + 2 写
        verify(redis, times(2)).executeVoid(any());   // outbox + 首批收件箱（失败后停用）
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING（首次），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散收件箱缓存失效失败"));
        assertNotNull(warnings.getFirst().getThrown(), "该链唯一捕获点 → 必须持栈");
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutThrowsOnUnexpectedError() {
        // feed3-T32：意外异常不再"末尾 SEVERE 兜底吞掉"——直接抛出，交消费容器重试 / 耗尽转死信
        // （比静默 ACK 更好：DLQ 留证据；本类不再记栈——重试终态由容器持栈）
        when(bigVRouter.isBigV(AUTHOR)).thenThrow(new IllegalStateException("boom"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> writer.fanout(CONTENT, AUTHOR));

        assertEquals("boom", thrown.getMessage(), "原异常原样穿透（不包装、不吞）");
        assertTrue(probe.records().isEmpty(), () -> "本类不重复记录（重试终态由容器持栈）: " + probe.records());
    }

    /** 评审 🟡2 统一捕获面：非 ServerException 的运行时出口（如池关闭 ISE）也记结论行后抛出。 */
    @Test
    void fanoutRecordsConclusionLineForNonServerRuntimeAndRethrows() throws SQLException {
        stubPages(List.of(List.of(11L)));
        when(feedInboxDao.insertIgnoreBatch(eq(conn), eq(CONTENT), any()))
                .thenThrow(new IllegalStateException("pool closed"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> writer.fanout(CONTENT, AUTHOR));

        assertEquals("pool closed", thrown.getMessage(), "原异常原样上抛（不包装）");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条结论行，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("交消费重试"));
        assertNull(warnings.getFirst().getThrown(), "结论行不带栈");
        assertTrue(probe.atLevel(Level.SEVERE).isEmpty(), "该边角在业务侧无 SEVERE（重试终态由容器持栈）");
    }

    // ==================== 降级补推（feed3-T28-B） ====================

    @Test
    void backfillWritesRecentContentsToFanBatchesByCursorAndInvalidatesInboxOnly() throws SQLException {
        List<Long> first = ids(11, 10 + BATCH);      // 满批 ⇒ 续游标
        List<Long> second = ids(20 + BATCH, 25 + BATCH);
        stubPages(List.of(first, second));
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K))
                .thenReturn(Map.of(AUTHOR, List.of(101L, 100L)));

        writer.backfillAuthor(AUTHOR);

        // 存量内容一次查询（K = 收件箱每作者窗口口径）
        verify(contentDao).findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K);
        // keyset 游标严格递增（0 → 首页末位），每批**逐内容** INSERT IGNORE（K 条语句 / 批）
        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 0L, BATCH);
        verify(followDao).getFollowerUserIdsAfter(conn, AUTHOR, 10L + BATCH, BATCH);
        verify(feedInboxDao).insertIgnoreBatch(conn, 101L, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, 100L, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, 101L, second);
        verify(feedInboxDao).insertIgnoreBatch(conn, 100L, second);
        // 每批一次收件箱失效（三件套，只碰 feed:inbox:*）——**不失效发件箱缓存**（补推不产生新内容）
        ArgumentCaptor<String[]> keysCaptor = ArgumentCaptor.forClass(String[].class);
        verify(jedis, times(2)).del(keysCaptor.capture());
        List<String[]> delCalls = keysCaptor.getAllValues();
        assertEquals(3 * first.size(), delCalls.get(0).length, "首批 DEL = 200 粉丝 × 3 键（三件套）");
        assertEquals(3 * second.size(), delCalls.get(1).length, "次批 DEL = 6 粉丝 × 3 键");
        for (String[] call : delCalls) {
            for (String key : call) {
                assertTrue(stripMarkerPrefix(key).startsWith(CacheKeys.FEED_INBOX_PREFIX), "越界 key: " + key);
            }
        }
        verify(jedis, never()).del(CacheKeys.feedOutboxCacheKeys(AUTHOR));
        assertEquals(List.of("DB", "DB", "DB", "DEL", "DB", "DB", "DEL"), events);
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    @Test
    void backfillSkipsWhenAuthorIsBigVAgainAtConsumeTime() throws SQLException {
        // 降级后又被关注回线（消费时已重升级）⇒ 早退：可见性由发件箱腿保证，不造无谓残影行
        when(bigVRouter.isBigV(AUTHOR)).thenReturn(true);

        writer.backfillAuthor(AUTHOR);

        verify(contentDao, never()).findRecentContentIdsByAuthor(any(), any(), anyInt());
        verify(followDao, never()).getFollowerUserIdsAfter(any(), anyLong(), anyLong(), anyInt());
        verify(feedInboxDao, never()).insertIgnoreBatch(any(), anyLong(), any());
        verify(redis, never()).executeVoid(any());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "跳过属常态路由，不应记 WARNING");
    }

    @Test
    void backfillNoOpWhenAuthorHasNoContent() throws SQLException {
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K))
                .thenReturn(Map.of());

        writer.backfillAuthor(AUTHOR);

        // 无存量内容 ⇒ 不读粉丝、不发落库、不失效缓存（只一次内容查询事务）
        verify(transactionTemplate, times(1)).execute(any());
        verify(followDao, never()).getFollowerUserIdsAfter(any(), anyLong(), anyLong(), anyInt());
        verify(feedInboxDao, never()).insertIgnoreBatch(any(), anyLong(), any());
        verify(redis, never()).executeVoid(any());
    }

    @Test
    void backfillThrowsOnContentLoadFailureWithSourceStack() throws SQLException {
        // feed3-T32：补推的存量内容读取失败不再吞掉——抛出交消费容器有限重试
        when(contentDao.findRecentContentIdsByAuthor(any(), any(), anyInt()))
                .thenThrow(new SQLException("db down"));

        ServerException thrown = assertThrows(ServerException.class, () -> writer.backfillAuthor(AUTHOR));

        assertTrue(thrown.getMessage().contains("降级补推存量内容查询失败"));
        assertSingleStackedSevere("降级补推存量内容查询失败");
        assertTrue(probe.atLevel(Level.WARNING).getFirst().getMessage().contains("交消费重试"));
        verify(followDao, never()).getFollowerUserIdsAfter(any(), anyLong(), anyLong(), anyInt());
        verify(redis, never()).executeVoid(any());
    }

    @Test
    void backfillThrowsOnCursorFailureWithSourceStack() throws SQLException {
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K))
                .thenReturn(Map.of(AUTHOR, List.of(101L)));
        when(followDao.getFollowerUserIdsAfter(any(), eq(AUTHOR), anyLong(), anyInt()))
                .thenThrow(new SQLException("db down"));

        ServerException thrown = assertThrows(ServerException.class, () -> writer.backfillAuthor(AUTHOR));

        assertTrue(thrown.getMessage().contains("写扩散粉丝游标查询失败"));
        assertSingleStackedSevere("写扩散粉丝游标查询失败");
        assertTrue(probe.atLevel(Level.WARNING).getFirst().getMessage().contains("交消费重试"));
        verify(feedInboxDao, never()).insertIgnoreBatch(any(), anyLong(), any());
        verify(redis, never()).executeVoid(any());
    }

    @Test
    void backfillThrowsOnDbWriteFailure() throws SQLException {
        stubPages(List.of(ids(1, BATCH), ids(BATCH + 1, BATCH + 10)));
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K))
                .thenReturn(Map.of(AUTHOR, List.of(101L)));
        when(feedInboxDao.insertIgnoreBatch(eq(conn), anyLong(), any()))
                .thenThrow(new SQLException("db down"));

        ServerException thrown = assertThrows(ServerException.class, () -> writer.backfillAuthor(AUTHOR));

        assertTrue(thrown.getMessage().contains("降级补推收件箱落库失败"));
        // 首批落库失败即抛出（不按批刷日志、不再续读）：只读了一页、无任何 DEL
        verify(followDao, times(1)).getFollowerUserIdsAfter(eq(conn), eq(AUTHOR), anyLong(), anyInt());
        verify(redis, never()).executeVoid(any());
        assertSingleStackedSevere("降级补推收件箱落库失败");
        assertTrue(probe.atLevel(Level.WARNING).getFirst().getMessage().contains("交消费重试"));
    }

    /** 评审 🟡2 统一捕获面（补推侧）：非 ServerException 的运行时出口同样记结论行后抛出。 */
    @Test
    void backfillRecordsConclusionLineForNonServerRuntimeAndRethrows() throws SQLException {
        when(contentDao.findRecentContentIdsByAuthor(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("pool closed"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> writer.backfillAuthor(AUTHOR));

        assertEquals("pool closed", thrown.getMessage(), "原异常原样上抛（不包装）");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条结论行，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("交消费重试"));
        assertNull(warnings.getFirst().getThrown(), "结论行不带栈");
        verify(followDao, never()).getFollowerUserIdsAfter(any(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void backfillAbandonsInboxInvalidationAfterFirstDelFailureButKeepsWriting() throws SQLException {
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH + 10);
        stubPages(List.of(first, second));
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(AUTHOR), K))
                .thenReturn(Map.of(AUTHOR, List.of(101L)));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> writer.backfillAuthor(AUTHOR));

        // DB 真相优先：两批都写完；DEL 只尝试一次（首次失败即停用），打点一次
        verify(feedInboxDao).insertIgnoreBatch(conn, 101L, first);
        verify(feedInboxDao).insertIgnoreBatch(conn, 101L, second);
        verify(redis, times(1)).executeVoid(any());
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL), "批量失败记一次（不逐批刷）");
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING（首次），实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散收件箱缓存失效失败"));
        assertTrue(warnings.getFirst().getMessage().contains("authorId=" + AUTHOR), "结论行须带 authorId 标识");
        assertNotNull(warnings.getFirst().getThrown(), "该链唯一捕获点 → 必须持栈");
    }

    @Test
    void backfillThrowsOnUnexpectedError() {
        // feed3-T32：意外异常不再"末尾 SEVERE 兜底吞掉"——直接抛出，交消费容器重试 / 耗尽转死信
        when(bigVRouter.isBigV(AUTHOR)).thenThrow(new IllegalStateException("boom"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> writer.backfillAuthor(AUTHOR));

        assertEquals("boom", thrown.getMessage(), "原异常原样穿透（不包装、不吞）");
        assertTrue(probe.records().isEmpty(), () -> "本类不重复记录（重试终态由容器持栈）: " + probe.records());
    }

    /** 断言"恰一条 SEVERE、含指定文案、持栈"——补推链各类失败共用的源头持栈口径。 */
    private void assertSingleStackedSevere(String messageFragment) {
        List<LogRecord> severe = probe.atLevel(Level.SEVERE);
        assertEquals(1, severe.size(), () -> "应恰一条 SEVERE，实际: " + probe.records());
        assertTrue(severe.getFirst().getMessage().contains(messageFragment),
                "SEVERE 文案: " + severe.getFirst().getMessage());
        assertNotNull(severe.getFirst().getThrown(), "源头（包装点）是该链唯一捕获点 → 持栈");
    }

    // ==================== 辅助 ====================

    /** 按调用顺序逐页返回（keyset 游标读；末次 stub 值会被重复返回，终止用例以空页/短页收束）。 */
    private void stubPages(List<List<Long>> pages) throws SQLException {
        OngoingStubbing<List<Long>> stub =
                when(followDao.getFollowerUserIdsAfter(any(Connection.class), eq(AUTHOR), anyLong(), eq(BATCH)));
        for (List<Long> page : pages) {
            stub = stub.thenReturn(page);
        }
    }

    /** 空页（显式类型，避免 List.of() 嵌套泛型推断歧义）。 */
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
