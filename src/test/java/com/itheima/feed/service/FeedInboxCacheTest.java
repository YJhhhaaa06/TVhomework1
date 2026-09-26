package com.itheima.feed.service;

import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.ZSetCache;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.follow.service.FollowCache;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FeedInboxCache} 单测（feed1-18 T18）：窗口迭代 / 批量写 / 幂等 / 两类降级 / FEED 域打点。
 *
 * <p>隔离手法：mock {@link FollowCache}（窗口读）与 {@link RedisAccess}（把回调打到 mock
 * {@link Jedis} + mock {@link Pipeline} 上），**不依赖真实 Redis / DB**；{@link CacheStats} 用真实件，
 * 以便断言"FEED 域可辨"。
 */
class FeedInboxCacheTest {

    private static final long AUTHOR = 9L;
    private static final long CONTENT = 42L;
    /** app.properties 的 feed.inbox.ttlMinutes 默认 60min（AppConfig 带默认值读取）。 */
    private static final long EXPECTED_TTL_SECONDS = 60L * 60;
    private static final int BATCH = FeedInboxCache.FANOUT_BATCH;

    private FollowCache followCache;
    private RedisAccess redis;
    private Jedis jedis;
    private Pipeline pipeline;
    private CacheStats stats;
    private FeedInboxCache cache;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        followCache = mock(FollowCache.class);
        redis = mock(RedisAccess.class);
        jedis = mock(Jedis.class);
        pipeline = mock(Pipeline.class);
        stats = new CacheStats();
        cache = new FeedInboxCache(followCache, redis, stats);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedInboxCache.class));
        when(jedis.pipelined()).thenReturn(pipeline);
        doAnswer(invocation -> {
            Consumer<Jedis> action = invocation.getArgument(0);
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
    void fanoutWritesInboxForEachFanInOneConnection() {
        stubWindows(List.of(List.of(11L, 12L)));

        cache.fanout(CONTENT, AUTHOR);

        verify(pipeline).zadd(CacheKeys.feedInbox(11L), CONTENT, String.valueOf(CONTENT));
        verify(pipeline).zadd(CacheKeys.feedInbox(12L), CONTENT, String.valueOf(CONTENT));
        verify(pipeline).expire(CacheKeys.feedInbox(11L), EXPECTED_TTL_SECONDS);
        verify(pipeline).expire(CacheKeys.feedInbox(12L), EXPECTED_TTL_SECONDS);
        verify(pipeline, times(1)).sync();
        // 一批粉丝 = 一次连接借用（不是逐粉丝各借还一次）
        verify(redis, times(1)).executeVoid(any());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutIteratesAllWindowsUntilShortPage() {
        List<Long> first = ids(1, BATCH);
        List<Long> second = ids(BATCH + 1, BATCH * 2);
        List<Long> third = ids(BATCH * 2 + 1, BATCH * 2 + 40);
        stubWindows(List.of(first, second, third));

        cache.fanout(CONTENT, AUTHOR);

        verify(followCache).getFollowerWindow(AUTHOR, 0L, BATCH);
        verify(followCache).getFollowerWindow(AUTHOR, BATCH, BATCH);
        verify(followCache).getFollowerWindow(AUTHOR, (long) BATCH * 2, BATCH);
        verify(followCache, times(3)).getFollowerWindow(eq(AUTHOR), anyLong(), eq(BATCH));
        verify(redis, times(3)).executeVoid(any());
        verify(pipeline, times(3)).sync();
        // 末批（40 条 < BATCH）也必须写完
        verify(pipeline).zadd(CacheKeys.feedInbox((long) BATCH * 2 + 40), CONTENT, String.valueOf(CONTENT));
    }

    @Test
    void fanoutStopsWhenFirstWindowEmpty() {
        stubWindows(List.of(emptyPage()));

        cache.fanout(CONTENT, AUTHOR);

        verify(followCache, times(1)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(redis, never()).executeVoid(any());
    }

    @Test
    void fanoutStopsAfterExactBatchFollowedByEmptyWindow() {
        // 边界：粉丝数恰为 BATCH 的整数倍 —— 靠"下一窗空"终止，不得死循环
        stubWindows(List.of(ids(1, BATCH), List.of()));

        cache.fanout(CONTENT, AUTHOR);

        verify(followCache).getFollowerWindow(AUTHOR, BATCH, BATCH);
        verify(followCache, times(2)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(redis, times(1)).executeVoid(any());
    }

    @Test
    void fanoutIsIdempotentAndWritesNoMarkerOrDelete() {
        // NEEDS 4.0"fanout 永远写"：幂等 ZADD，不检查存在性、不 DEL、不写完整态标记（属 T19）
        stubWindows(List.of(List.of(11L)));

        cache.fanout(CONTENT, AUTHOR);
        cache.fanout(CONTENT, AUTHOR);

        verify(pipeline, times(2)).zadd(CacheKeys.feedInbox(11L), CONTENT, String.valueOf(CONTENT));
        verify(pipeline, times(2)).expire(CacheKeys.feedInbox(11L), EXPECTED_TTL_SECONDS);
        verify(pipeline, times(2)).sync();
        verifyNoMoreInteractions(pipeline);
    }

    @Test
    void fanoutTouchesOnlyFeedInboxKeys() {
        // 红线：不改读路径 —— 只碰 feed:inbox:*，不写 content:* / user:* / empty:* / partial:*
        stubWindows(List.of(List.of(11L, 12L, 13L)));

        cache.fanout(CONTENT, AUTHOR);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(pipeline, atLeastOnce()).zadd(keys.capture(), anyDouble(), anyString());
        verify(pipeline, atLeastOnce()).expire(keys.capture(), anyLong());
        assertEquals(6, keys.getAllValues().size(), "3 粉丝 × (zadd + expire)");
        for (String key : keys.getAllValues()) {
            assertTrue(key.startsWith(CacheKeys.FEED_INBOX_PREFIX), "越界 key: " + key);
        }
    }

    // ==================== 降级路径 ====================

    @Test
    void fanoutDegradesOnRedisFailure() {
        stubWindows(List.of(List.of(11L, 12L)));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> cache.fanout(CONTENT, AUTHOR));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散收件箱写入失败"));
        assertNotNull(warnings.getFirst().getThrown(), "Redis 写失败是该链唯一捕获点 → 必须持栈");
        // 批量失败记一次（不逐粉丝刷），dataKey 归 FEED 域 ⇒「FEED 域可辨」
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutStopsIteratingAfterFirstWriteFailure() {
        // Redis 全不可用时，不得把"窗口 DB 查 + 降级日志"按批数各刷一遍（放大 ∝ 粉丝数）：
        // 首批写失败即短路本次 fanout，未写到的粉丝由 T19 重建兜底（一期无人读收件箱，无对外影响）
        stubWindows(List.of(ids(1, BATCH), ids(BATCH + 1, BATCH + 10)));
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());

        assertDoesNotThrow(() -> cache.fanout(CONTENT, AUTHOR));

        verify(followCache, times(1)).getFollowerWindow(eq(AUTHOR), anyLong(), anyInt());
        verify(redis, times(1)).executeVoid(any());
        assertEquals(1, probe.atLevel(Level.WARNING).size(), "只应记一次写失败（不按批刷）");
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
    }

    @Test
    void fanoutDegradesOnFollowerWindowFailureWithoutDoubleStack() {
        when(followCache.getFollowerWindow(eq(AUTHOR), anyLong(), anyInt()))
                .thenThrow(new DatabaseException("db down"));

        assertDoesNotThrow(() -> cache.fanout(CONTENT, AUTHOR));

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散中止"));
        assertNull(warnings.getFirst().getThrown(), "源头（FollowCache.loadIds）已持栈 → 此处为结论行、不带栈");
        verify(redis, never()).executeVoid(any());
        assertEquals(0L, stats.count(CacheDomain.FEED, CacheStats.Event.WRITE_FAIL));
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
