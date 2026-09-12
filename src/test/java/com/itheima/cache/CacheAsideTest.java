package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheAsideTest {

    /** 真实组件组合 + mock MyRedisPool/Jedis（不碰真实 Redis，沿用 LikeCacheServiceTest 范式）。 */
    private final CacheAside cache = new CacheAside(new RedisAccess(), new JacksonCodec(),
            new SingleFlight(), new CacheStats());

    static class SampleDto {
        private long id;
        private String name;

        SampleDto() {
        }

        SampleDto(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
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
    private static Response<String> strResponse(String v) {
        Response<String> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    /**
     * 单 key 一趟 pipeline 桩（T8 读路径已 pipeline 化）：EXISTS 空标记 + GET 数据 key。
     * 注意：Response mock 必须先于 Pipeline stub 创建（避免 thenReturn 内嵌 stubbing）。
     *
     * @param empty 空标记 EXISTS 结果
     * @param json  数据 key GET 结果（null = 无数据）
     */
    private static Pipeline stubProbe(Jedis jedis, Boolean empty, String json) {
        Response<Boolean> emptyResp = boolResponse(empty);
        Response<String> jsonResp = strResponse(json);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(anyString())).thenReturn(emptyResp);
        when(p.get(anyString())).thenReturn(jsonResp);
        return p;
    }

    /** 令单次读降级（Redis 访问异常 → CacheException → 降级）。 */
    private static void stubRedisDown(Jedis jedis) {
        when(jedis.pipelined()).thenThrow(new RuntimeException("redis down"));
    }

    // ==================== read() 三态 ====================

    @Test
    void readHitEmptyReturnsHitEmptyWithoutReadingData() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, true, null);

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.HIT_EMPTY, result.status());
            assertNull(result.value());
            verify(jedis, never()).get(anyString());
        }
    }

    @Test
    void readHitDataParsesJsonValue() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, new JacksonCodec().toJson(new SampleDto(1L, "alice")));

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.HIT_DATA, result.status());
            assertEquals(1L, result.value().getId());
            assertEquals("alice", result.value().getName());
            // T8：单 key 读一趟 pipeline（EXISTS+GET 合一趟往返），不再逐命令往返
            verify(jedis, times(1)).pipelined();
            verify(jedis, never()).exists(anyString());
            verify(jedis, never()).get(anyString());
        }
    }

    @Test
    void readMissWhenNoKeysExist() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.MISS, result.status());
            assertNull(result.value());
        }
    }

    @Test
    void readRedisErrorDegradesToMissWithoutThrowing() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.MISS, result.status());
            assertNull(result.value());
        }
    }

    // ==================== get() Cache-Aside 读 ====================

    @Test
    void getHitEmptyReturnsNullWithoutLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, true, null);
            AtomicInteger loads = new AtomicInteger();

            assertNull(cache.get("content:1", SampleDto.class, () -> {
                loads.incrementAndGet();
                return new SampleDto(1L, "x");
            }, 100));

            assertEquals(0, loads.get());
            verify(jedis, never()).get(anyString());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void getHitDataReturnsParsedWithoutLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, new JacksonCodec().toJson(new SampleDto(1L, "alice")));
            AtomicInteger loads = new AtomicInteger();

            SampleDto value = cache.get("content:1", SampleDto.class, () -> {
                loads.incrementAndGet();
                return new SampleDto(2L, "loader");
            }, 100);

            assertEquals(1L, value.getId());
            assertEquals("alice", value.getName());
            assertEquals(0, loads.get());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void getMissLoadsWritesDataKeyWithJitteredTtl() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);
            SampleDto dto = new SampleDto(1L, "alice");

            SampleDto value = cache.get("content:1", SampleDto.class, () -> dto, 100);

            assertSame(dto, value);
            String expectedJson = new JacksonCodec().toJson(dto);
            // ±10% 抖动：100s -> [90,110]
            verify(jedis).setex(eq("content:1"), longThat(t -> t >= 90 && t <= 110), eq(expectedJson));
        }
    }

    @Test
    void getMissLoaderNullWritesEmptyMarkerAndDeletesDataKey() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);

            assertNull(cache.get("content:1", SampleDto.class, () -> null, 100));

            verify(jedis).setex("empty:content:1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis).del("content:1");
            verify(jedis, never()).setex(eq("content:1"), anyLong(), anyString());
        }
    }

    @Test
    void getTypeReferenceVariantLoadsGenericList() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);
            List<SampleDto> list = List.of(new SampleDto(1L, "a"));

            List<SampleDto> value = cache.get("content:comments:1",
                    new TypeReference<List<SampleDto>>() {
                    },
                    () -> list, 100);

            assertSame(list, value);
            verify(jedis).setex(eq("content:comments:1"), anyLong(), anyString());
        }
    }

    @Test
    void getReadRedisErrorDegradesToLoaderWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);
            SampleDto dto = new SampleDto(1L, "db");

            SampleDto value = cache.get("content:1", SampleDto.class, () -> dto, 100);

            assertSame(dto, value);
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
            verify(jedis, never()).del(anyString());
        }
    }

    // ==================== getBatch() 批量读（T8） ====================

    @Test
    void getBatchMixedStatesReturnsMapWithSingleRoundTrip() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            // k1/k2 hit-data、k3 hit-empty（空标记）、k4 miss（loader 回填）
            String json1 = new JacksonCodec().toJson(new SampleDto(1L, "one"));
            String json2 = new JacksonCodec().toJson(new SampleDto(2L, "two"));
            // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing）
            Response<Boolean> e1 = boolResponse(false);
            Response<Boolean> e2 = boolResponse(false);
            Response<Boolean> e3 = boolResponse(true);
            Response<Boolean> e4 = boolResponse(false);
            Response<String> j1 = strResponse(json1);
            Response<String> j2 = strResponse(json2);
            Response<String> j3 = strResponse(null);
            Response<String> j4 = strResponse(null);
            when(p.exists("empty:k1")).thenReturn(e1);
            when(p.exists("empty:k2")).thenReturn(e2);
            when(p.exists("empty:k3")).thenReturn(e3);
            when(p.exists("empty:k4")).thenReturn(e4);
            when(p.get("k1")).thenReturn(j1);
            when(p.get("k2")).thenReturn(j2);
            when(p.get("k3")).thenReturn(j3);
            when(p.get("k4")).thenReturn(j4);

            Map<String, SampleDto> result = cache.getBatch(
                    List.of("k1", "k2", "k3", "k4"), SampleDto.class,
                    k -> new SampleDto(9L, "loader-" + k), 100);

            assertEquals(4, result.size());
            assertEquals(1L, result.get("k1").getId());
            assertEquals("two", result.get("k2").getName());
            assertNull(result.get("k3")); // hit-empty 返回 null，不查 DB
            assertEquals("loader-k4", result.get("k4").getName()); // miss 逐个单飞回填
            // 一趟 pipeline 拉全部（治 H12）
            verify(jedis, times(1)).pipelined();
            verify(jedis, never()).exists(anyString());
            verify(jedis, never()).get(anyString());
        }
    }

    @Test
    void getBatchAllHitEmptySkipsLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> hitEmpty = boolResponse(true);
            when(p.exists(anyString())).thenReturn(hitEmpty);
            AtomicInteger loads = new AtomicInteger();

            Map<String, SampleDto> result = cache.getBatch(
                    List.of("k1", "k2"), SampleDto.class,
                    k -> {
                        loads.incrementAndGet();
                        return new SampleDto(1L, "x");
                    }, 100);

            assertEquals(2, result.size());
            assertNull(result.get("k1"));
            assertNull(result.get("k2"));
            assertEquals(0, loads.get());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void getBatchMissNullLoaderWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> notEmpty = boolResponse(false);
            Response<String> noJson = strResponse(null);
            when(p.exists("empty:k1")).thenReturn(notEmpty);
            when(p.get("k1")).thenReturn(noJson);

            Map<String, SampleDto> result = cache.getBatch(List.of("k1"), SampleDto.class,
                    k -> null, 100);

            assertNull(result.get("k1"));
            verify(jedis).setex("empty:k1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis).del("k1");
        }
    }

    @Test
    void getBatchPipelineErrorDegradesAllKeysWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            Map<String, SampleDto> result = cache.getBatch(List.of("k1", "k2"), SampleDto.class,
                    k -> new SampleDto(7L, "db-" + k), 100);

            assertEquals(2, result.size());
            assertEquals("db-k1", result.get("k1").getName());
            assertEquals("db-k2", result.get("k2").getName());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void getBatchParseErrorDegradesOnlyThatKey() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> e1 = boolResponse(false);
            Response<Boolean> e2 = boolResponse(false);
            Response<String> dirtyJson = strResponse("not-a-json"); // 脏 JSON
            Response<String> goodJson = strResponse(new JacksonCodec().toJson(new SampleDto(2L, "two")));
            when(p.exists("empty:k1")).thenReturn(e1);
            when(p.exists("empty:k2")).thenReturn(e2);
            when(p.get("k1")).thenReturn(dirtyJson);
            when(p.get("k2")).thenReturn(goodJson);

            Map<String, SampleDto> result = cache.getBatch(List.of("k1", "k2"), SampleDto.class,
                    k -> new SampleDto(8L, "loader-" + k), 100);

            // 脏 key 降级走 loader，干净 key 正常命中，互不影响
            assertEquals("loader-k1", result.get("k1").getName());
            assertEquals("two", result.get("k2").getName());
        }
    }

    @Test
    void getBatchEmptyOrNullInputReturnsEmptyMapWithoutRedis() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            assertTrue(cache.getBatch(Collections.emptyList(), SampleDto.class, k -> null, 100).isEmpty());
            assertTrue(cache.getBatch(null, SampleDto.class, k -> null, 100).isEmpty());

            verify(jedis, never()).pipelined();
        }
    }

    // ==================== T9 滑动续期：命中续期（原 TTL 抖动）、空标记不续、失败降级 ====================

    @Test
    void getHitDataRenewsTtlInSingleRoundTripWithoutRenewingEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbe(jedis, false, new JacksonCodec().toJson(new SampleDto(1L, "alice")));

            SampleDto value = cache.get("content:1", SampleDto.class, () -> new SampleDto(2L, "loader"), 100);

            assertEquals(1L, value.getId());
            // 命中数据 key 顺带续期：续期值=原 TTL ±10% 抖动（100s -> [90,110]），仍一趟往返
            verify(p).expire(eq("content:1"), longThat(t -> t >= 90 && t <= 110));
            verify(jedis, times(1)).pipelined();
            // 空标记 key 从不被续期
            verify(p, never()).expire(startsWith("empty:"), anyLong());
        }
    }

    @Test
    void getHitEmptyNeverRenewsEmptyMarkerAndSkipsLoader() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = stubProbe(jedis, true, null);
            AtomicInteger loads = new AtomicInteger();

            assertNull(cache.get("content:1", SampleDto.class, () -> {
                loads.incrementAndGet();
                return new SampleDto(1L, "x");
            }, 100));

            assertEquals(0, loads.get());
            // hit-empty：data key 不存在，EXPIRE 返回 0 无效果（语义无害）；empty: key 从未被续期
            verify(p).expire(eq("content:1"), longThat(t -> t >= 90 && t <= 110));
            verify(p, never()).expire(startsWith("empty:"), anyLong());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    @Test
    void getBatchRenewsEachDataKeyWithoutRenewingEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> e1 = boolResponse(false);
            Response<Boolean> e2 = boolResponse(true);
            Response<String> j1 = strResponse(new JacksonCodec().toJson(new SampleDto(1L, "one")));
            Response<String> j2 = strResponse(null);
            when(p.exists("empty:k1")).thenReturn(e1);
            when(p.exists("empty:k2")).thenReturn(e2);
            when(p.get("k1")).thenReturn(j1);
            when(p.get("k2")).thenReturn(j2);

            cache.getBatch(List.of("k1", "k2"), SampleDto.class, k -> null, 100);

            // 每个 data key 命中（hit-data / hit-empty）均入列续期；empty: key 从不续期
            verify(p).expire(eq("k1"), longThat(t -> t >= 90 && t <= 110));
            verify(p).expire(eq("k2"), longThat(t -> t >= 90 && t <= 110));
            verify(p, never()).expire(startsWith("empty:"), anyLong());
            verify(jedis, times(1)).pipelined();
        }
    }

    @Test
    void getRenewalFailureDegradesToLoaderWithoutThrowing() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> notEmpty = boolResponse(false);
            Response<String> json = strResponse(new JacksonCodec().toJson(new SampleDto(1L, "alice")));
            when(p.exists(anyString())).thenReturn(notEmpty);
            when(p.get(anyString())).thenReturn(json);
            doThrow(new RuntimeException("redis expire down")).when(p).sync();

            SampleDto value = cache.get("content:1", SampleDto.class, () -> new SampleDto(2L, "db"), 100);

            // EXPIRE 引发的 pipeline 失败（Redis 异常）不影响读返回：降级走 loader、不写回
            assertEquals(2L, value.getId());
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        }
    }

    // ==================== 写路径：写失败=DEL 降级 ====================

    @Test
    void writeOrInvalidateOnSuccessSetsDataKey() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.writeOrInvalidate("content:1", new SampleDto(1L, "alice"), 100);

            verify(jedis).setex(eq("content:1"), longThat(t -> t >= 90 && t <= 110),
                    eq(new JacksonCodec().toJson(new SampleDto(1L, "alice"))));
        }
    }

    @Test
    void writeOrInvalidateClearsEmptyMarkerToAvoidFakeEmpty() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.writeOrInvalidate("content:1", new SampleDto(1L, "alice"), 100);

            verify(jedis).del("empty:content:1");

            // 写数据后再读：空标记已清，读路径不再返回假空
            stubProbe(jedis, false, new JacksonCodec().toJson(new SampleDto(1L, "alice")));
            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);
            assertEquals(CacheStatus.HIT_DATA, result.status());
            assertEquals(1L, result.value().getId());
        }
    }

    @Test
    void writeOrInvalidateOnFailureDeletesDataAndEmptyKeysWithoutThrowing() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.setex(anyString(), anyLong(), anyString()))
                    .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(
                    () -> cache.writeOrInvalidate("content:1", new SampleDto(1L, "alice"), 100));

            verify(jedis).del("content:1");
            verify(jedis).del("empty:content:1");
        }
    }

    @Test
    void markEmptyWritesEmptyMarkerAndDeletesDataKey() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.markEmpty("content:1");

            verify(jedis).setex("empty:content:1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis).del("content:1");
        }
    }

    @Test
    void invalidateDeletesDataAndEmptyKeysBestEffort() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            cache.invalidate("content:1");

            verify(jedis).del("content:1");
            verify(jedis).del("empty:content:1");
        }
    }

    @Test
    void invalidateToleratesRedisFailureWithoutThrowing() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.del(anyString())).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> cache.invalidate("content:1"));
            assertDoesNotThrow(() -> cache.markEmpty("content:1"));
        }
    }

    // ==================== T7 统计接线（CacheAside 自动打点） ====================

    @Test
    void getHitDataRecordsHitDataForDomain() {
        CacheStats stats = new CacheStats();
        CacheAside c = new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, new JacksonCodec().toJson(new SampleDto(1L, "alice")));

            c.get("content:1", SampleDto.class, () -> new SampleDto(2L, "loader"), 100);

            assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.HIT_DATA));
            assertEquals(0, stats.count(CacheDomain.CONTENT, CacheStats.Event.MISS));
            assertEquals(1, stats.totalAccesses());
        }
    }

    @Test
    void getMissRecordsMissAndLoad() {
        CacheStats stats = new CacheStats();
        CacheAside c = new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);

            SampleDto value = c.get("content:1", SampleDto.class, () -> new SampleDto(1L, "db"), 100);

            assertEquals(1L, value.getId());
            assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.MISS));
            assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.LOAD));
        }
    }

    @Test
    void getRedisErrorRecordsDegradeAndStillServesByLoader() {
        CacheStats stats = new CacheStats();
        CacheAside c = new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            SampleDto value = c.get("content:1", SampleDto.class, () -> new SampleDto(1L, "db"), 100);

            assertEquals(1L, value.getId());
            assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.DEGRADE));
            assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.LOAD));
        }
    }

    // ==================== T8 统计（批量读打点与单 key 口径一致） ====================

    @Test
    void getBatchRecordsStatsPerKey() {
        CacheStats stats = new CacheStats();
        CacheAside c = new CacheAside(new RedisAccess(), new JacksonCodec(), new SingleFlight(), stats);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> notEmpty = boolResponse(false);
            Response<String> json1 = strResponse(new JacksonCodec().toJson(new SampleDto(1L, "one")));
            Response<String> noJson = strResponse(null);
            when(p.exists(anyString())).thenReturn(notEmpty);
            when(p.get("k1")).thenReturn(json1);
            when(p.get("k2")).thenReturn(noJson);

            c.getBatch(List.of("k1", "k2"), SampleDto.class, k -> null, 100);

            assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.HIT_DATA)); // k1
            assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.MISS));    // k2
            assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.LOAD));    // k2 miss 回填
            assertEquals(3, stats.totalAccesses());
        }
    }
}