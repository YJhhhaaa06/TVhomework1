package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.util.MyRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ZSetCache 基建组件单测（第六期 T7 / A1「缓存有序结构」新增）：三态/回填/降级/批量 +
 * **按序窗口读**（ZRANGE[start,stop] + ZCARD）/ 续期语义 / 统计打点。
 *
 * <p>范式对齐 {@code SetCacheTest}：真实组件组合 + mock MyRedisPool/Jedis（不碰真实 Redis）；
 * 降级用例用 mock RedisAccess 恒抛 CacheException（MockedStatic 线程局部不可跨线程）。
 * 与 SetCache 的差异只在命令层（ZSCORE/ZRANGE/ZADD）与"有序 + 窗口"能力。
 */
class ZSetCacheTest {

    /** 共享实例（非统计断言用例）；统计断言用 {@link #newCache(CacheStats)} 新建隔离实例。 */
    private final ZSetCache cache = newCache(new CacheStats());

    private static ZSetCache newCache(CacheStats stats) {
        return new ZSetCache(new RedisAccess(),
                new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), stats),
                new SingleFlight(), stats);
    }

    private static Jedis mockJedis(MockedStatic<MyRedisPool> ms) {
        Jedis jedis = mock(Jedis.class);
        ms.when(() -> MyRedisPool.getJedis()).thenReturn(jedis);
        return jedis;
    }

    @SuppressWarnings("unchecked")
    private static Response<Boolean> boolResponse(Boolean v) {
        Response<Boolean> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Response<Double> scoreResponse(Double v) {
        Response<Double> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Response<Long> longResponse(Long v) {
        Response<Long> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Response<List<String>> listResponse(List<String> v) {
        Response<List<String>> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    /** 单成员三态扫描桩：一趟 pipeline [empty, exists, zscore]，返回 pipeline 供续期断言。 */
    private static Pipeline stubScan(Jedis jedis, String zsetKey, String member,
                                     Boolean empty, Boolean exists, Double score) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<Double> s = scoreResponse(score);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists("empty:" + zsetKey)).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        when(p.zscore(zsetKey, member)).thenReturn(s);
        return p;
    }

    /** 全量/窗口探测桩：一趟 pipeline [empty, exists]。 */
    private static Pipeline stubProbe(Jedis jedis, String zsetKey, Boolean empty, Boolean exists) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists("empty:" + zsetKey)).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        return p;
    }

    /** 窗口命中桩：与探针共用同一 pipeline（同一 mock 承载两段），返回 pipeline 供续期断言。 */
    private static Pipeline stubProbeAndWindow(Jedis jedis, String zsetKey, Boolean empty, Boolean exists,
                                               long start, long stop, List<String> page, Long card) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<List<String>> r = listResponse(page);
        Response<Long> c = longResponse(card);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists("empty:" + zsetKey)).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        when(p.zrange(zsetKey, start, stop)).thenReturn(r);
        when(p.zcard(zsetKey)).thenReturn(c);
        return p;
    }

    /** miss 回填侧桩：写路径直接 Jedis zadd+expire。 */
    private static void stubWriteBack(Jedis jedis) {
        when(jedis.zadd(anyString(), anyMap())).thenReturn(1L);
        when(jedis.expire(anyString(), anyLong())).thenReturn(1L);
    }

    /** 令单次读降级（Redis 访问异常 → CacheException → 降级）。 */
    private static void stubRedisDown(Jedis jedis) {
        when(jedis.pipelined()).thenThrow(new RuntimeException("redis down"));
    }

    /** 降级用例：mock RedisAccess 恒抛 CacheException。 */
    private static ZSetCache newDegradedCache(CacheStats stats) {
        RedisAccess down = mock(RedisAccess.class);
        when(down.execute(any())).thenThrow(new CacheException("redis down"));
        doThrow(new CacheException("redis down")).when(down).executeVoid(any());
        return new ZSetCache(down,
                new CacheAside(down, new JacksonCodec(), new SingleFlight(), stats),
                new SingleFlight(), stats);
    }

    // ==================== isMember：单成员三态 ====================

    @Test
    void isMemberHitEmptyReturnsFalseWithoutLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubScan(jedis, "key", "42", true, false, null);

            assertFalse(cache.isMember("key", 42L, () -> {
                fail("hit-empty 不应调用 loader");
                return List.of();
            }, 100));

            verify(p).expire("key", 100L); // 续期入列（key 不存在返回 0 无效果）
            verify(p, never()).expire(startsWith("empty:"), anyLong());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void isMemberHitDataMemberPresentReturnsTrue() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, true, 42.0);

            assertTrue(cache.isMember("key", 42L, () -> {
                fail("hit-data 不应调用 loader");
                return List.of();
            }, 100));
        }
    }

    @Test
    void isMemberHitDataMemberAbsentReturnsFalse() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, true, null); // ZSCORE null = 非成员

            assertFalse(cache.isMember("key", 42L, () -> {
                fail("hit-data 不应调用 loader");
                return List.of();
            }, 100));
        }
    }

    @Test
    void isMemberMissWritesZAddWithMemberAsScoreAndExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, false, null);
            stubWriteBack(jedis);

            assertTrue(cache.isMember("key", 42L, () -> List.of(1L, 42L), 100));

            // score = 成员自身数值（有序读口径的唯一来源）
            verify(jedis).zadd("key", Map.of("1", 1.0, "42", 42.0));
            verify(jedis).expire("key", 100L); // 回填精确 TTL 无抖动
        }
    }

    @Test
    void isMemberMissEmptyLoaderWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, false, null);
            when(jedis.exists("key")).thenReturn(false); // markEmpty exists 守卫
            when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");

            assertFalse(cache.isMember("key", 42L, () -> List.of(), 100));

            verify(jedis).setex("empty:key", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    @Test
    void isMemberRedisErrorDegradesToLoaderWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            assertTrue(cache.isMember("key", 42L, () -> List.of(42L), 100));

            verify(jedis, never()).zadd(anyString(), anyMap());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    // ==================== getMembers：全量读（ZRANGE 升序） ====================

    @Test
    void getMembersHitEmptyReturnsEmptyList() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "key", true, false);

            assertEquals(Collections.emptyList(), cache.getMembers("key", () -> List.of(9L), 100));

            verify(jedis, never()).zrange(anyString(), anyLong(), anyLong());
        }
    }

    @Test
    void getMembersHitDataReturnsAscendingZrange() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbe(jedis, "key", false, true);
            when(jedis.zrange("key", 0, -1)).thenReturn(List.of("1", "2", "10"));

            List<Long> result = cache.getMembers("key", () -> List.of(9L), 100);

            // ZRANGE 0 -1 按 score 升序 → 与既有"按 id 升序"口径一致（此处故意用 10 验证非字典序）
            assertEquals(List.of(1L, 2L, 10L), result);
            verify(p).expire("key", 100L);
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    @Test
    void getMembersMissLoadsAndWritesZSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "key", false, false);
            stubWriteBack(jedis);

            List<Long> result = cache.getMembers("key", () -> List.of(5L), 100);

            assertEquals(List.of(5L), result);
            verify(jedis).zadd("key", Map.of("5", 5.0));
            verify(jedis).expire("key", 100L);
        }
    }

    @Test
    void getMembersRedisErrorDegradesWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            assertEquals(List.of(7L), cache.getMembers("key", () -> List.of(7L), 100));

            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    // ==================== getWindow：按序窗口（T7 A1 分页载体） ====================

    @Test
    void getWindowHitDataTakesPageAndTotalInOnePipeline() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", false, true, 2L, 3L,
                    List.of("8", "9"), 7L);

            ZSetCache.Window window = cache.getWindow("key", 2L, 2, () -> {
                fail("hit-data 不应调用 loader");
                return List.of();
            }, 100);

            assertEquals(List.of(8L, 9L), window.getIds());
            assertEquals(7L, window.getTotal());
            verify(p).zrange("key", 2L, 3L); // [offset, offset+count-1]
            verify(p).zcard("key");
            verify(p).expire("key", 100L); // 命中续期（同 SetCache 语义）
        }
    }

    @Test
    void getWindowHitEmptyReturnsEmptyWindowWithZeroTotal() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", true, false, 0L, 9L, null, null);

            ZSetCache.Window window = cache.getWindow("key", 0L, 10, () -> List.of(9L), 100);

            assertTrue(window.getIds().isEmpty());
            assertEquals(0L, window.getTotal());
            verify(p, never()).zrange(anyString(), anyLong(), anyLong());
            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    @Test
    void getWindowInvalidArgsSkipsRedisEntirely() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            assertEquals(0L, cache.getWindow("key", -1L, 10, () -> List.of(9L), 100).getTotal());
            assertTrue(cache.getWindow("key", 0L, 0, () -> List.of(9L), 100).getIds().isEmpty());

            verify(jedis, never()).pipelined();
        }
    }

    @Test
    void getWindowMissBackfillsThenReadsWindow() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", false, false, 0L, 1L, List.of("8", "9"), 2L);
            stubWriteBack(jedis);

            ZSetCache.Window window = cache.getWindow("key", 0L, 2, () -> List.of(8L, 9L), 100);

            assertEquals(List.of(8L, 9L), window.getIds());
            assertEquals(2L, window.getTotal());
            verify(jedis).zadd("key", Map.of("8", 8.0, "9", 9.0)); // 先回填，再重读窗口
            verify(p).zrange("key", 0L, 1L); // 窗口读在 pipeline 上（一趟往返）
        }
    }

    @Test
    void getWindowMissEmptyLoaderReturnsEmptyWindowWithoutWindowRead() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", false, false, 0L, 9L, null, null);
            when(jedis.exists("key")).thenReturn(false);
            when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");

            ZSetCache.Window window = cache.getWindow("key", 0L, 10, () -> List.of(), 100);

            assertTrue(window.getIds().isEmpty());
            assertEquals(0L, window.getTotal());
            verify(jedis).setex("empty:key", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(p, never()).zrange(anyString(), anyLong(), anyLong());
        }
    }

    @Test
    void getWindowRedisErrorDegradesWithAscendingSliceAndTotal() {
        CacheStats stats = new CacheStats();
        ZSetCache degraded = newDegradedCache(stats);

        // loader 乱序（DB 无 ORDER BY）→ 降级路径按 score 口径升序切片，与 hit-data 的 ZRANGE 序一致
        ZSetCache.Window window = degraded.getWindow("user:following:1", 1L, 2,
                () -> List.of(9L, 3L, 8L), 100);

        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(3L, window.getTotal());
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.DEGRADE));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void getWindowOffsetBeyondTotalReturnsEmptyButKeepsTotal() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "key", false, true, 5L, 6L, Collections.emptyList(), 3L);

            ZSetCache.Window window = cache.getWindow("key", 5L, 2, () -> List.of(9L), 100);

            assertTrue(window.getIds().isEmpty());
            assertEquals(3L, window.getTotal());
        }
    }

    // ==================== writeZSet：回填 ====================

    @Test
    void writeZSetNonEmptyZaddZscoresMemberValuesAndExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.writeZSet("key", List.of(3L, 8L), 100);

            verify(jedis).zadd("key", Map.of("3", 3.0, "8", 8.0));
            verify(jedis).expire("key", 100L);
        }
    }

    @Test
    void writeZSetEmptyWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("key")).thenReturn(false);
            when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");

            cache.writeZSet("key", List.of(), 100);

            verify(jedis).setex("empty:key", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    @Test
    void writeZSetRedisErrorRecordsWriteFailWithoutThrowing() {
        CacheStats stats = new CacheStats();
        ZSetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.zadd(anyString(), anyMap())).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> c.writeZSet("user:following:1", List.of(1L), 100));

            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.WRITE_FAIL));
        }
    }

    // ==================== batchIsMember：单 key 多成员（Follow 形态） ====================

    @Test
    void batchIsMemberHitDataPerMemberByZscore() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(true);
            Response<Double> s1 = scoreResponse(1.0);
            Response<Double> s2 = scoreResponse(null);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.zscore("key", "1")).thenReturn(s1);
            when(p.zscore("key", "2")).thenReturn(s2);

            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L, 2L),
                    missed -> {
                        fail("hit-data 不应调用 dbAnswer");
                        return Set.of();
                    },
                    () -> {
                        fail("hit-data 不应调用 fullLoader");
                        return List.of();
                    }, 100);

            assertTrue(result.get(1L));
            assertFalse(result.get(2L));
            verify(p).expire("key", 100L);
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    @Test
    void batchIsMemberMissUsesDbAnswerAndBackfillsAllOnce() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Double> s1 = scoreResponse(null);
            Response<Double> s2 = scoreResponse(null);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.zscore("key", "1")).thenReturn(s1);
            when(p.zscore("key", "2")).thenReturn(s2);
            stubWriteBack(jedis);

            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L, 2L),
                    missed -> {
                        assertEquals(List.of(1L, 2L), missed);
                        return Set.of(2L);
                    },
                    () -> List.of(1L, 2L, 3L), 100);

            assertFalse(result.get(1L));
            assertTrue(result.get(2L));
            verify(jedis).zadd("key", Map.of("1", 1.0, "2", 2.0, "3", 3.0)); // 同一 key 只回填一次（全量）
        }
    }

    @Test
    void batchIsMemberDegradeLoadsViaSingleFlightWithoutWriteBack() {
        CacheStats stats = new CacheStats();
        ZSetCache degraded = newDegradedCache(stats);

        Map<Long, Boolean> result = degraded.batchIsMember("user:following:1", List.of(1L, 2L),
                missed -> Set.of(),
                () -> List.of(1L), 100);

        assertTrue(result.get(1L));
        assertFalse(result.get(2L));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.DEGRADE));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void batchIsMemberEmptyInputReturnsEmptyMap() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            assertTrue(cache.batchIsMember("key", Collections.emptyList(),
                    missed -> Set.of(), () -> List.of(), 100).isEmpty());
            assertTrue(cache.batchIsMember("key", null,
                    missed -> Set.of(), () -> List.of(), 100).isEmpty());

            verify(jedis, never()).pipelined();
        }
    }

    // ==================== 统计接线 ====================

    @Test
    void getWindowMissRecordsMissAndLoadOnFollowDomain() {
        CacheStats stats = new CacheStats();
        ZSetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "user:following:1", false, false, 0L, 1L, List.of("8"), 1L);
            stubWriteBack(jedis);

            c.getWindow("user:following:1", 0L, 2, () -> List.of(8L), 100);

            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
        }
    }

    @Test
    void getWindowHitDataRecordsHitData() {
        CacheStats stats = new CacheStats();
        ZSetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "user:following:1", false, true, 0L, 1L, List.of("8"), 1L);

            c.getWindow("user:following:1", 0L, 2, () -> List.of(8L), 100);

            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.HIT_DATA));
            assertEquals(0, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
        }
    }
}
