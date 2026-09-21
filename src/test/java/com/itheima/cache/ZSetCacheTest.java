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
 *
 * <p><b>T11-C（前缀窗口装载，治 U-18）</b>：窗口读的 miss / 部分态 / 降级三支由"全量装载"
 * 改为"只看本页"（miss 取 {@code [0, offset+count)}、部分态补 {@code [W, offset+count)}、
 * 降级 DB 窗口直查），集合完整性由 {@code partial:{key}} 标记表达。本文件新增该组用例：
 * 装载量只含前缀、标记置/清、部分态判定回落 DB、全量读遇部分态补齐、降级不装载不写回。
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

    // ==================== 桩：探针 / 窗口 / 写回 / 降级 ====================

    /** 单成员三态扫描桩：一趟 pipeline [empty, exists, zscore, partial=false]，返回 pipeline 供续期断言。 */
    private static Pipeline stubScan(Jedis jedis, String zsetKey, String member,
                                     Boolean empty, Boolean exists, Double score) {
        return stubScan(jedis, zsetKey, member, empty, exists, score, false);
    }

    /** 单成员三态扫描桩（带部分装载标记）。 */
    private static Pipeline stubScan(Jedis jedis, String zsetKey, String member,
                                     Boolean empty, Boolean exists, Double score, Boolean partial) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<Double> s = scoreResponse(score);
        Response<Boolean> pt = boolResponse(partial);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        when(p.zscore(zsetKey, member)).thenReturn(s);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(pt);
        return p;
    }

    /** 全量/窗口探测桩：一趟 pipeline [empty, exists, partial=false]。 */
    private static Pipeline stubProbe(Jedis jedis, String zsetKey, Boolean empty, Boolean exists) {
        return stubProbe(jedis, zsetKey, empty, exists, false);
    }

    /** 全量/窗口探测桩（带部分装载标记）。 */
    private static Pipeline stubProbe(Jedis jedis, String zsetKey, Boolean empty, Boolean exists,
                                      Boolean partial) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<Boolean> pt = boolResponse(partial);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(pt);
        return p;
    }

    /** 窗口命中桩：探针 + 窗口 pipeline 的 zrange/zcard（同一 mock Pipeline 承载两段）。 */
    private static Pipeline stubProbeAndWindow(Jedis jedis, String zsetKey, Boolean empty, Boolean exists,
                                               Boolean partial, long start, long stop,
                                               List<String> page, Long card) {
        Response<Boolean> e = boolResponse(empty);
        Response<Boolean> x = boolResponse(exists);
        Response<Boolean> pt = boolResponse(partial);
        Response<List<String>> r = listResponse(page);
        Response<Long> c = longResponse(card);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(e);
        when(p.exists(zsetKey)).thenReturn(x);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(pt);
        when(p.zrange(zsetKey, start, stop)).thenReturn(r);
        when(p.zcard(zsetKey)).thenReturn(c);
        return p;
    }

    /** miss 回填侧桩：写路径直接 Jedis zadd+expire / setex（partial 标记）。 */
    private static void stubWriteBack(Jedis jedis) {
        when(jedis.zadd(anyString(), anyMap())).thenReturn(1L);
        when(jedis.expire(anyString(), anyLong())).thenReturn(1L);
        when(jedis.setex(anyString(), anyLong(), anyString())).thenReturn("OK");
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

    /** 恒不调用的窗口 loader（用于"不应装载"的用例）。 */
    private static ZSetCache.WindowLoader failLoader() {
        return (from, size) -> {
            fail("本用例不应触发窗口装载");
            return List.of();
        };
    }

    /** 恒不调用的 total loader（完整态一律 ZCARD，不该走计数口径）。 */
    private static java.util.function.Supplier<Long> failTotal() {
        return () -> {
            fail("完整态不应取域级计数口径");
            return 0L;
        };
    }

    /** 记录调用的窗口 loader。 */
    private static final class RecordingLoader implements ZSetCache.WindowLoader {
        final List<long[]> calls = new ArrayList<>();
        private final List<Long> rows;

        RecordingLoader(List<Long> rows) {
            this.rows = rows;
        }

        @Override
        public List<Long> load(long offset, int count) {
            calls.add(new long[]{offset, count});
            return rows;
        }
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
            }, () -> {
                fail("hit-empty 不应回落 DB");
                return false;
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
            }, () -> {
                fail("命中不应回落 DB");
                return false;
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
            }, () -> {
                fail("完整态未命中即可信，不应回落 DB");
                return false;
            }, 100));
        }
    }

    @Test
    void isMemberMissWritesZAddWithMemberAsScoreAndExactTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "key", "42", false, false, null);
            stubWriteBack(jedis);

            assertTrue(cache.isMember("key", 42L, () -> List.of(1L, 42L), () -> false, 100));

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

            assertFalse(cache.isMember("key", 42L, () -> List.of(), () -> false, 100));

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

            assertTrue(cache.isMember("key", 42L, () -> List.of(42L), () -> false, 100));

            verify(jedis, never()).zadd(anyString(), anyMap());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    // ==================== isMember：T11-C 部分装载态判定回落 ====================

    @Test
    void isMemberPartialMissFallsBackToDbAnswer() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "user:following:1", "42", false, true, null, true); // 部分态 + ZSCORE 未命中

            assertTrue(cache.isMember("user:following:1", 42L, () -> {
                fail("部分态未命中不得走全量 loader");
                return List.of();
            }, () -> true, 100), "部分态未命中必须回落 DB，不能直接答 false");
        }
    }

    @Test
    void isMemberPartialHitTrustsZscoreWithoutDbAnswer() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubScan(jedis, "user:following:1", "42", false, true, 42.0, true); // 部分态但命中

            assertTrue(cache.isMember("user:following:1", 42L, () -> List.of(),
                    () -> {
                        fail("ZSCORE 命中即成员，部分态下同样可信，不应回落 DB");
                        return false;
                    }, 100));
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

    // ==================== getMembers：T11-C 部分态必须补齐（R3 最危险点） ====================

    @Test
    void getMembersPartialCompletesWithFullLoaderAndClearsMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbe(jedis, "user:following:1", false, true, true); // 部分态
            when(jedis.zrange("user:following:1", 0, -1)).thenReturn(List.of("1", "2", "5", "9"));
            stubWriteBack(jedis);
            List<Integer> fullLoads = new ArrayList<>();

            List<Long> result = cache.getMembers("user:following:1", () -> {
                fullLoads.add(1);
                return List.of(1L, 2L, 5L, 9L); // 真全量（feed 拉关注 ids 依赖它）
            }, 100);

            assertEquals(List.of(1L, 2L, 5L, 9L), result, "部分态必须补齐后再返回，不得返回前缀");
            assertEquals(1, fullLoads.size(), "补齐应走一次全量 loader");
            verify(jedis).zadd(eq("user:following:1"), anyMap()); // 合并写入（不 DEL，避免丢并发写）
            verify(jedis).del("partial:user:following:1");         // 补齐后清标记 → 转完整态
            verify(p).expire("user:following:1", 100L);
        }
    }

    @Test
    void getMembersPartialReturnsDbListEvenWhenWriteBackFails() {
        // R3 回归（评审 🔴 修复）：补齐的缓存写回是 best-effort —— 写失败也必须返回 **DB 全量**，
        // 绝不能"回读 Redis"作答（那只能拿到被标记为前缀的那部分 → feed 静默漏关注者）
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, "user:following:1", false, true, true);
            when(jedis.zadd(anyString(), anyMap())).thenThrow(new RuntimeException("redis down"));
            when(jedis.zrange("user:following:1", 0, -1)).thenReturn(List.of("1")); // 缓存里只有前缀 1 条

            List<Long> result = cache.getMembers("user:following:1", () -> List.of(1L, 2L, 5L, 9L), 100);

            assertEquals(List.of(1L, 2L, 5L, 9L), result,
                    "写回失败也必须返回 DB 全量（feed 依赖全量关注 ids，漏人会静默降级内容覆盖）");
        }
    }

    // ==================== getWindow：按序窗口（T7 A1 分页载体） ====================

    @Test
    void getWindowHitDataTakesPageAndTotalInOnePipeline() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", false, true, false, 2L, 3L,
                    List.of("8", "9"), 7L);

            ZSetCache.Window window = cache.getWindow("key", 2L, 2, failLoader(), failTotal(), 100);

            assertEquals(List.of(8L, 9L), window.getIds());
            assertEquals(7L, window.getTotal(), "完整态 total 必须仍走 ZCARD（与 T7 逐字节一致）");
            verify(p).zrange("key", 2L, 3L); // [offset, offset+count-1]
            verify(p).zcard("key");
            verify(p).expire("key", 100L); // 命中续期（同 SetCache 语义）
        }
    }

    @Test
    void getWindowHitEmptyReturnsEmptyWindowWithZeroTotal() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbeAndWindow(jedis, "key", true, false, false, 0L, 9L, null, null);

            ZSetCache.Window window = cache.getWindow("key", 0L, 10, failLoader(), failTotal(), 100);

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

            assertEquals(0L, cache.getWindow("key", -1L, 10, failLoader(), failTotal(), 100).getTotal());
            assertTrue(cache.getWindow("key", 0L, 0, failLoader(), failTotal(), 100).getIds().isEmpty());

            verify(jedis, never()).pipelined();
        }
    }

    @Test
    void getWindowRedisErrorDegradesToDbWindowQueryWithoutLoad() {
        CacheStats stats = new CacheStats();
        ZSetCache degraded = newDegradedCache(stats);
        RecordingLoader loader = new RecordingLoader(List.of(8L, 9L));

        ZSetCache.Window window = degraded.getWindow("user:following:1", 1L, 2, loader, () -> 30L, 100);

        // T11-C 降级语义：DB **窗口直查**（只查被看的那一段）、不装载、不写回
        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(1, loader.calls.size());
        assertEquals(1L, loader.calls.get(0)[0]);
        assertEquals(2L, loader.calls.get(0)[1]);
        assertEquals(30L, window.getTotal(), "降级态 total 走域级计数口径");
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.DEGRADE));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void getWindowOffsetBeyondTotalReturnsEmptyButKeepsTotal() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "key", false, true, false, 5L, 6L, Collections.emptyList(), 3L);

            ZSetCache.Window window = cache.getWindow("key", 5L, 2, failLoader(), failTotal(), 100);

            assertTrue(window.getIds().isEmpty());
            assertEquals(3L, window.getTotal());
        }
    }

    // ==================== getWindow：T11-C 前缀窗口装载（partial 标记） ====================

    @Test
    void getWindowMissLoadsOnlyPrefixAndMarksPartial() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // miss 后：装载 [0, offset+count)=[0,2) → ZCARD=2（本页落在已知前缀内）→ 再读该页
            stubProbeAndWindow(jedis, "user:following:1", false, false, false, 0L, 1L, null, null);
            when(jedis.zcard("user:following:1")).thenReturn(2L);
            when(jedis.zrange("user:following:1", 0L, 1L)).thenReturn(List.of("7", "9"));
            stubWriteBack(jedis);
            RecordingLoader loader = new RecordingLoader(List.of(7L, 9L));

            ZSetCache.Window window = cache.getWindow("user:following:1", 0L, 2, loader, () -> 500000L, 100);

            assertEquals(List.of(7L, 9L), window.getIds());
            assertEquals(1, loader.calls.size(), "miss 只装载一次");
            assertEquals(0L, loader.calls.get(0)[0], "冷 key 从 0 开始装载");
            assertEquals(2L, loader.calls.get(0)[1], "装载量 = offset+count（不是列表总量）");
            verify(jedis).zadd("user:following:1", Map.of("7", 7.0, "9", 9.0)); // 只回填前缀
            verify(jedis).setex("partial:user:following:1", 100L, CacheKeys.PARTIAL_MARKER_VALUE);
            verify(jedis, never()).zrange("user:following:1", 0L, -1L); // 不触发全量读
            assertEquals(500000L, window.getTotal(), "部分态 total 走域级计数口径（ZCARD 只是前缀）");
        }
    }

    @Test
    void getWindowMissCompleteLoadClearsPartialAndUsesZcard() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // 同一 pipeline 同时承载探针（miss）与回填后的窗口读（readWindow）
            stubProbeAndWindow(jedis, "user:following:1", false, false, false, 0L, 9L,
                    List.of("7", "9"), 2L);
            stubWriteBack(jedis);
            RecordingLoader loader = new RecordingLoader(List.of(7L, 9L));

            // DB 只返回 2 行（< 请求 10）→ 已到底 → 完整态
            ZSetCache.Window window = cache.getWindow("user:following:1", 0L, 10, loader, failTotal(), 100);

            assertEquals(List.of(7L, 9L), window.getIds());
            assertEquals(2L, window.getTotal(), "装载即到底 → 完整态 → ZCARD 口径（T7 一致）");
            verify(jedis).zadd("user:following:1", Map.of("7", 7.0, "9", 9.0));
            verify(jedis, never()).setex(eq("partial:user:following:1"), anyLong(), anyString());
            verify(jedis).del("partial:user:following:1"); // 完整态：清残留标记
        }
    }

    @Test
    void getWindowMissEmptyLoadWritesEmptyMarkerWithoutPartial() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "user:following:1", false, false, false,
                    0L, 9L, Collections.emptyList(), 0L);
            when(jedis.exists("user:following:1")).thenReturn(false); // markEmpty 守卫
            stubWriteBack(jedis);

            ZSetCache.Window window = cache.getWindow("user:following:1", 0L, 10,
                    (from, size) -> List.of(), () -> 0L, 100);

            assertTrue(window.getIds().isEmpty());
            assertEquals(0L, window.getTotal());
            verify(jedis).setex("empty:user:following:1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).zadd(anyString(), anyMap());
            verify(jedis, never()).setex(eq("partial:user:following:1"), anyLong(), anyString());
        }
    }

    @Test
    void getWindowPartialWithinPrefixNeedsNoLoad() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "user:follower:9", false, true, true, 0L, 1L, null, null);
            when(jedis.zcard("user:follower:9")).thenReturn(200L);      // 已装载 200 条
            when(jedis.zrange("user:follower:9", 0L, 1L)).thenReturn(List.of("3", "4"));

            ZSetCache.Window window = cache.getWindow("user:follower:9", 0L, 2,
                    failLoader(), () -> 1000000L, 100);

            assertEquals(List.of(3L, 4L), window.getIds());
            assertEquals(1000000L, window.getTotal(), "部分态 total 用计数口径，不是 ZCARD=200");
            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    @Test
    void getWindowPartialBeyondPrefixAppendsOnlyWindowGap() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbeAndWindow(jedis, "user:follower:9", false, true, true, 4L, 5L, null, null);
            when(jedis.zcard("user:follower:9")).thenReturn(2L); // 已知前缀 W=2（补齐前）
            when(jedis.zrange("user:follower:9", 4L, 5L)).thenReturn(List.of("30", "40"));
            stubWriteBack(jedis);
            // DB 侧 [2,6) 共 4 行：取满 → 未到底 → 仍是部分态（不清标记）
            RecordingLoader loader = new RecordingLoader(List.of(20L, 25L, 30L, 40L));

            ZSetCache.Window window = cache.getWindow("user:follower:9", 4L, 2, loader, () -> 900L, 100);

            assertEquals(List.of(30L, 40L), window.getIds());
            assertEquals(1, loader.calls.size());
            assertEquals(2L, loader.calls.get(0)[0], "补齐从水位 W=2 开始，不重装前缀");
            assertEquals(4L, loader.calls.get(0)[1], "只补 [W, offset+count) = [2,6) 这一段");
            verify(jedis).zadd("user:follower:9", Map.of("20", 20.0, "25", 25.0, "30", 30.0, "40", 40.0));
            verify(jedis).setex("partial:user:follower:9", 100L, CacheKeys.PARTIAL_MARKER_VALUE);
            assertEquals(900L, window.getTotal());
        }
    }

    @Test
    void getWindowPartialReachingEndClearsMarkerAndUsesZcard() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // 探针：部分态；补齐后转完整 → 走 readWindow（同一 pipeline 的 zrange/zcard 桩）
            stubProbeAndWindow(jedis, "user:follower:9", false, true, true, 0L, 9L,
                    List.of("3", "4", "5"), 3L);
            when(jedis.zcard("user:follower:9")).thenReturn(2L);
            stubWriteBack(jedis);
            RecordingLoader loader = new RecordingLoader(List.of(5L)); // 只补到 1 条 → 到底

            ZSetCache.Window window = cache.getWindow("user:follower:9", 0L, 10, loader, failTotal(), 100);

            assertEquals(List.of(3L, 4L, 5L), window.getIds());
            assertEquals(3L, window.getTotal(), "补齐到底 → 完整态 → ZCARD 口径");
            verify(jedis).del("partial:user:follower:9");
            verify(jedis, never()).setex(eq("partial:user:follower:9"), anyLong(), anyString());
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
            Response<Boolean> pt = boolResponse(false);
            Response<Double> s1 = scoreResponse(1.0);
            Response<Double> s2 = scoreResponse(null);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists(CacheKeys.empty("key"))).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.exists(CacheKeys.partial("key"))).thenReturn(pt);
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
    void batchIsMemberPartialMissesFallBackToDbWithoutFullBackfill() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(true);
            Response<Boolean> pt = boolResponse(true); // 部分装载态
            Response<Double> s1 = scoreResponse(1.0);
            Response<Double> s2 = scoreResponse(null);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists(CacheKeys.empty("key"))).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.exists(CacheKeys.partial("key"))).thenReturn(pt);
            when(p.zscore("key", "1")).thenReturn(s1);
            when(p.zscore("key", "2")).thenReturn(s2);

            Map<Long, Boolean> result = cache.batchIsMember("key", List.of(1L, 2L),
                    missed -> {
                        assertEquals(List.of(2L), missed, "只有未命中成员才回落 DB");
                        return Set.of(2L);
                    },
                    () -> {
                        fail("部分态不得回填全量（U-18：装载量不再放大到 O(总量)）");
                        return List.of();
                    }, 100);

            assertEquals(true, result.get(1L));
            assertEquals(true, result.get(2L));
            verify(jedis, never()).zadd(anyString(), anyMap());
        }
    }

    @Test
    void batchIsMemberMissUsesDbAnswerAndBackfillsAllOnce() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Response<Boolean> e = boolResponse(false);
            Response<Boolean> x = boolResponse(false);
            Response<Boolean> pt = boolResponse(false);
            Response<Double> s1 = scoreResponse(null);
            Response<Double> s2 = scoreResponse(null);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            when(p.exists(CacheKeys.empty("key"))).thenReturn(e);
            when(p.exists("key")).thenReturn(x);
            when(p.exists(CacheKeys.partial("key"))).thenReturn(pt);
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
            stubProbeAndWindow(jedis, "user:following:1", false, false, false, 0L, 1L,
                    List.of("8", "9"), 2L);
            when(jedis.zcard("user:following:1")).thenReturn(2L);
            stubWriteBack(jedis);

            c.getWindow("user:following:1", 0L, 2, (from, size) -> List.of(8L, 9L), () -> 2L, 100);

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
            stubProbeAndWindow(jedis, "user:following:1", false, true, false, 0L, 1L,
                    List.of("8"), 1L);

            c.getWindow("user:following:1", 0L, 2, failLoader(), failTotal(), 100);

            assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.HIT_DATA));
            assertEquals(0, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
        }
    }
}
