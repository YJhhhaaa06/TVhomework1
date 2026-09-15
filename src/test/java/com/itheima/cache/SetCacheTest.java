package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.util.MyRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SetCache 基建组件单测（第四期 T1）：三态/回填/降级/批量（含空标记与续期语义）/统计打点。
 *
 * <p>范式对齐 CacheAsideTest：真实组件组合 + mock MyRedisPool/Jedis（不碰真实 Redis）；
 * 并发/降级用例用 mock RedisAccess 恒抛 CacheException（MockedStatic 线程局部不可跨线程）。
 */
class SetCacheTest {

    /** 共享实例（非统计断言用例）；统计断言用 {@link #newCache(CacheStats)} 新建隔离实例。 */
    private final SetCache cache = new SetCache(new RedisAccess(),
            new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), new CacheStats()),
            new SingleFlight(), new CacheStats());

    private static SetCache newCache(CacheStats stats) {
        return new SetCache(new RedisAccess(),
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

    /** 单成员三态扫描桩：一趟 pipeline [empty, exists, member]，返回 pipeline 供续期断言。 */
    private static Pipeline stubScan(Jedis jedis, String setKey, String member,
                                     Boolean empty, Boolean exists, Boolean memberPresent) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<Boolean> m = boolResponse(memberPresent);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists("empty:" + setKey)).thenReturn(e);
        when(p.exists(setKey)).thenReturn(x);
        when(p.sismember(setKey, member)).thenReturn(m);
        return p;
    }

    /** 全量读探测桩：一趟 pipeline [empty, exists]。 */
    private static Pipeline stubProbe(Jedis jedis, String setKey, Boolean empty, Boolean exists) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists("empty:" + setKey)).thenReturn(e);
        when(p.exists(setKey)).thenReturn(x);
        return p;
    }

    /** miss 回填侧桩：写路径直接 Jedis sadd+expire。 */
    private static void stubWriteBack(Jedis jedis) {
        when(jedis.sadd(anyString(), any(String[].class))).thenReturn(1L);
        when(jedis.expire(anyString(), anyLong())).thenReturn(1L);
    }

    /** 令单次读降级（Redis 访问异常 → CacheException → 降级）。 */
    private static void stubRedisDown(Jedis jedis) {
        when(jedis.pipelined()).thenThrow(new RuntimeException("redis down"));
    }

    /** 降级/并发用例：mock RedisAccess 恒抛 CacheException（同 CacheAsideTest 范式）。 */
    @SuppressWarnings("unchecked")
    private static SetCache newDegradedCache(CacheStats stats) {
        RedisAccess down = mock(RedisAccess.class);
        when(down.execute(any())).thenThrow(new CacheException("redis down"));
        doThrow(new CacheException("redis down")).when(down).executeVoid(any());
        return new SetCache(down,
                new CacheAside(down, new JacksonCodec(), new SingleFlight(), stats),
                new SingleFlight(), stats);
    }

    // ==================== isMember：单成员三态 ====================

    @Test
    void isMemberHitEmptyReturnsFalseWithoutLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubScan(jedis, "key", "42", true, false, false);
            AtomicInteger loads = new AtomicInteger();

            assertFalse(cache.isMember("key", 42L, () -> {
                loads.incrementAndGet();
                return List.of(42L);
            }, 100));

            assertEquals(0, loads.get());
            verify(p).expire("key", 100L); // 续期入列（数据 key 不存在返回 0 无效果）
            verify(p, never()).expire(startsWith("empty:"), anyLong());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void isMemberHitDataMemberPresentReturnsTrue() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, true, true);

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
            stubScan(jedis, "key", "42", false, true, false);

            assertFalse(cache.isMember("key", 42L, () -> {
                fail("hit-data 不应调用 loader");
                return List.of();
            }, 100));
        }
    }

    @Test
    void isMemberMissLoadsAndWritesSetWithExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, false, false);
            stubWriteBack(jedis);
            AtomicInteger loads = new AtomicInteger();

            assertTrue(cache.isMember("key", 42L, () -> {
                loads.incrementAndGet();
                return List.of(1L, 42L);
            }, 100));

            assertEquals(1, loads.get());
            verify(jedis).sadd("key", "1", "42");
            verify(jedis).expire("key", 100L); // 回填精确 TTL 无抖动
        }
    }

    @Test
    void isMemberMissEmptyLoaderWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, false, false);
            when(jedis.exists("key")).thenReturn(false); // markEmpty exists 守卫
            when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");

            assertFalse(cache.isMember("key", 42L, () -> List.of(), 100));

            verify(jedis).setex("empty:key", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).sadd(anyString(), any(String[].class));
        }
    }

    @Test
    void isMemberRedisErrorDegradesToLoaderWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            assertTrue(cache.isMember("key", 42L, () -> List.of(42L), 100));

            verify(jedis, never()).sadd(anyString(), any(String[].class));
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void isMemberRedisErrorConcurrentSameKeyLoadsLoaderOnce() throws Exception {
        SetCache degraded = newDegradedCache(new CacheStats());
        int threads = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> degraded.isMember("key", 42L, () -> {
                    loads.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return List.of(42L);
                }, 100)));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // 等其余线程全部进入单飞等待（对齐 SingleFlightTest 放大并发窗口）
            release.countDown();
            for (Future<Boolean> f : futures) {
                assertTrue(f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== getMembers：全量读 ====================

    @Test
    void getMembersHitEmptyReturnsEmptyList() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "key", true, false);

            assertEquals(Collections.emptyList(), cache.getMembers("key", () -> List.of(9L), 100));

            verify(jedis, never()).smembers(anyString());
        }
    }

    @Test
    void getMembersHitDataReturnsMembersFromSmembers() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "key", false, true);
            when(jedis.smembers("key")).thenReturn(Set.of("1", "2"));

            List<Long> result = cache.getMembers("key", () -> List.of(9L), 100);

            assertEquals(Set.of(1L, 2L), Set.copyOf(result));
            verify(jedis).smembers("key");
        }
    }

    @Test
    void getMembersMissLoadsAndWritesSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "key", false, false);
            stubWriteBack(jedis);

            List<Long> result = cache.getMembers("key", () -> List.of(5L), 100);

            assertEquals(List.of(5L), result);
            verify(jedis).sadd("key", "5");
            verify(jedis).expire("key", 100L);
        }
    }

    @Test
    void getMembersRedisErrorDegradesWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            assertEquals(List.of(7L), cache.getMembers("key", () -> List.of(7L), 100));

            verify(jedis, never()).sadd(anyString(), any(String[].class));
        }
    }

    // ==================== writeSet：回填 ====================

    @Test
    void writeSetNonEmptySaddAndExpireExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.writeSet("key", List.of(1L, 2L), 100);

            verify(jedis).sadd("key", "1", "2");
            verify(jedis).expire("key", 100L); // 精确 TTL 无抖动
        }
    }

    @Test
    void writeSetEmptyWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("key")).thenReturn(false); // markEmpty exists 守卫
            when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");

            cache.writeSet("key", List.of(), 100);

            verify(jedis).setex("empty:key", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).sadd(anyString(), any(String[].class));
        }
    }

    @Test
    void writeSetRedisErrorRecordsWriteFailWithoutThrowing() {
        CacheStats stats = new CacheStats();
        SetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.sadd(anyString(), any(String[].class)))
                    .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> c.writeSet("content:likeSet:1", List.of(1L), 100));

            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.WRITE_FAIL));
        }
    }

    // ==================== batchIsMember：单 set 多成员（Follow 形态） ====================

    @Test
    void batchIsMemberHitEmptyAllFalse() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing）
            Response<Boolean> empty = boolResponse(true);
            Response<Boolean> member = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(empty);
            when(p.sismember(anyString(), anyString())).thenReturn(member);
            AtomicInteger loads = new AtomicInteger();

            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L, 2L),
                    missed -> {
                        fail("hit-empty 不应调用 dbAnswer");
                        return Set.of();
                    },
                    () -> {
                        loads.incrementAndGet();
                        return List.of();
                    }, 100);

            assertEquals(2, result.size());
            assertFalse(result.get(1L));
            assertFalse(result.get(2L));
            assertEquals(0, loads.get());
            verify(p).expire("key", 100L); // 续期入列
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    @Test
    void batchIsMemberHitDataPerMember() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(true);
            Response<Boolean> m1 = boolResponse(true);
            Response<Boolean> m2 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.sismember("key", "1")).thenReturn(m1);
            when(p.sismember("key", "2")).thenReturn(m2);

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
        }
    }

    @Test
    void batchIsMemberMissUsesDbAnswerAndBackfillsOnce() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Boolean> m1 = boolResponse(false);
            Response<Boolean> m2 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.sismember("key", "1")).thenReturn(m1);
            when(p.sismember("key", "2")).thenReturn(m2);
            stubWriteBack(jedis);

            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L, 2L),
                    missed -> {
                        assertEquals(List.of(1L, 2L), missed);
                        return Set.of(2L);
                    },
                    () -> List.of(1L, 2L, 3L), 100);

            assertFalse(result.get(1L));
            assertTrue(result.get(2L));
            verify(jedis).sadd("key", "1", "2", "3"); // 同一 set 只回填一次（全量）
            verify(jedis).expire("key", 100L);
        }
    }

    @Test
    void batchIsMemberBackfillFailureBestEffortKeepsAnswer() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Boolean> m1 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:key")).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.sismember("key", "1")).thenReturn(m1);

            // fullLoader（回填写）失败：best-effort，DB 答案照常返回，不 500
            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L),
                    missed -> Set.of(1L),
                    () -> {
                        throw new IllegalStateException("db down");
                    }, 100);

            assertTrue(result.get(1L));
        }
    }

    @Test
    void batchIsMemberDegradeLoadsViaSingleFlightWithoutWriteBack() {
        CacheStats stats = new CacheStats();
        SetCache degraded = newDegradedCache(stats);

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

    @Test
    void batchIsMemberMissRecordsStatsOncePerBatch() {
        CacheStats stats = new CacheStats();
        SetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Boolean> m1 = boolResponse(false);
            Response<Boolean> m2 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:user:following:1")).thenReturn(e);
            when(p.exists("user:following:1")).thenReturn(x);
            when(p.sismember("user:following:1", "1")).thenReturn(m1);
            when(p.sismember("user:following:1", "2")).thenReturn(m2);

            c.batchIsMember("user:following:1", List.of(1L, 2L), missed -> Set.of(), () -> List.of(), 100);

            // 单 set 批量一趟记一次（不按成员重复打点）
            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
        }
    }

    // ==================== batchKeysIsMember：多 set 单成员（Like 形态） ====================

    @Test
    void batchKeysIsMemberMixedStatesWithDbAnswerAndBackfill() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // k1 hit-empty、k2 hit-data（成员在）、k3 miss
            Response<Boolean> e1 = boolResponse(true);
            Response<Boolean> e2 = boolResponse(false);
            Response<Boolean> e3 = boolResponse(false);
            Response<Boolean> x2 = boolResponse(true);
            Response<Boolean> x3 = boolResponse(false);
            Response<Boolean> m2 = boolResponse(true);
            Response<Boolean> m3 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:k1")).thenReturn(e1);
            when(p.exists("empty:k2")).thenReturn(e2);
            when(p.exists("empty:k3")).thenReturn(e3);
            when(p.exists("k2")).thenReturn(x2);
            when(p.exists("k3")).thenReturn(x3);
            when(p.sismember("k2", "42")).thenReturn(m2);
            when(p.sismember("k3", "42")).thenReturn(m3);
            stubWriteBack(jedis);

            Map<String, Boolean> result = cache.batchKeysIsMember(List.of("k1", "k2", "k3"), 42L,
                    missed -> {
                        assertEquals(List.of("k3"), missed);
                        return Set.of("k3");
                    },
                    key -> {
                        assertEquals("k3", key);
                        return List.of(42L);
                    }, 100);

            assertEquals(3, result.size());
            assertFalse(result.get("k1"));
            assertTrue(result.get("k2"));
            assertTrue(result.get("k3")); // dbAnswer 作答 + 逐 key 回填
            verify(jedis).sadd("k3", "42");
            verify(jedis).expire("k3", 100L);
        }
    }

    @Test
    void batchKeysIsMemberBackfillFailureBestEffortKeepsAnswer() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Boolean> m = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:k1")).thenReturn(e);
            when(p.exists("k1")).thenReturn(x);
            when(p.sismember("k1", "42")).thenReturn(m);

            Map<String, Boolean> result = cache.batchKeysIsMember(List.of("k1"), 42L,
                    missed -> Set.of("k1"),
                    key -> {
                        throw new IllegalStateException("db down");
                    }, 100);

            assertTrue(result.get("k1"));
        }
    }

    @Test
    void batchKeysIsMemberDegradePerKeySingleFlight() {
        CacheStats stats = new CacheStats();
        SetCache degraded = newDegradedCache(stats);

        Map<String, Boolean> result = degraded.batchKeysIsMember(
                List.of("content:likeSet:1", "content:likeSet:2"), 42L,
                missed -> Set.of(),
                key -> List.of(42L), 100);

        assertTrue(result.get("content:likeSet:1"));
        assertTrue(result.get("content:likeSet:2"));
        assertEquals(2, stats.count(CacheDomain.LIKE, CacheStats.Event.DEGRADE));
        assertEquals(2, stats.count(CacheDomain.LIKE, CacheStats.Event.LOAD));
    }

    @Test
    void batchKeysIsMemberEmptyInputReturnsEmptyMap() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            assertTrue(cache.batchKeysIsMember(Collections.emptyList(), 42L,
                    missed -> Set.of(), key -> List.of(), 100).isEmpty());
            assertTrue(cache.batchKeysIsMember(null, 42L,
                    missed -> Set.of(), key -> List.of(), 100).isEmpty());

            verify(jedis, never()).pipelined();
        }
    }

    @Test
    void batchKeysIsMemberRecordsStatsPerKey() {
        CacheStats stats = new CacheStats();
        SetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // k1 hit-empty、k2 hit-data、k3 miss+load
            Response<Boolean> e1 = boolResponse(true);
            Response<Boolean> e2 = boolResponse(false);
            Response<Boolean> e3 = boolResponse(false);
            Response<Boolean> x2 = boolResponse(true);
            Response<Boolean> x3 = boolResponse(false);
            Response<Boolean> m2 = boolResponse(true);
            Response<Boolean> m3 = boolResponse(false);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists("empty:content:likeSet:1")).thenReturn(e1);
            when(p.exists("empty:content:likeSet:2")).thenReturn(e2);
            when(p.exists("empty:content:likeSet:3")).thenReturn(e3);
            when(p.exists("content:likeSet:2")).thenReturn(x2);
            when(p.exists("content:likeSet:3")).thenReturn(x3);
            when(p.sismember("content:likeSet:2", "42")).thenReturn(m2);
            when(p.sismember("content:likeSet:3", "42")).thenReturn(m3);

            c.batchKeysIsMember(List.of("content:likeSet:1", "content:likeSet:2", "content:likeSet:3"),
                    42L, missed -> Set.of(), key -> List.of(42L), 100);

            // 多 set 批量每 (key, 决策) 记一次
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_EMPTY));
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_DATA));
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.MISS));
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.LOAD));
        }
    }

    // ==================== T9 续期语义（命中续期精确 TTL、空标记不续） ====================

    @Test
    void getMembersHitDataRenewsSetKeyWithExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbe(jedis, "user:following:1", false, true);
            when(jedis.smembers("user:following:1")).thenReturn(Set.of("1"));

            cache.getMembers("user:following:1", () -> List.of(9L), 100);

            verify(p).expire("user:following:1", 100L); // 命中 set 顺带续期（精确 TTL 无抖动）
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    @Test
    void isMemberHitDataRenewsSetKeyWithExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubScan(jedis, "key", "42", false, true, true);

            assertTrue(cache.isMember("key", 42L, () -> List.of(42L), 100));

            verify(p).expire("key", 100L);
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    // ==================== 统计接线（单成员路径） ====================

    @Test
    void isMemberMissRecordsMissAndLoad() {
        CacheStats stats = new CacheStats();
        SetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "content:likeSet:1", "42", false, false, false);
            stubWriteBack(jedis);

            c.isMember("content:likeSet:1", 42L, () -> List.of(42L), 100);

            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.MISS));
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.LOAD));
        }
    }

    @Test
    void isMemberRedisErrorRecordsDegrade() {
        CacheStats stats = new CacheStats();
        SetCache c = newCache(stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            c.isMember("content:likeSet:1", 42L, () -> List.of(42L), 100);

            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.DEGRADE));
            assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.LOAD));
        }
    }
}
