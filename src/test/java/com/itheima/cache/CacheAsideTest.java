package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

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
    void getMissLoaderNullWritesEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);
            // 三期 T4/N3：markEmpty 含存在守卫（数据 key 不存在→写空标记），del 已随守卫移除
            when(jedis.exists("content:1")).thenReturn(false);

            assertNull(cache.get("content:1", SampleDto.class, () -> null, 100));

            verify(jedis).setex("empty:content:1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).del(anyString());
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

    // ==================== 三期 T3 负缓存治理：加载失败 ≠ 确认无数据 ====================

    @Test
    void getMissLoaderDbFailureReturnsNullWithoutEmptyMarkerOrDel() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubProbe(jedis, false, null);

            assertNull(cache.get("content:1", SampleDto.class,
                    () -> { throw new DatabaseException("db down"); }, 100));

            // 加载失败 ≠ 确认无数据：不写空标记、不 DEL 数据 key（读路径不固化瞬时故障）
            verify(jedis, never()).setex(eq("empty:content:1"), anyLong(), anyString());
            verify(jedis, never()).del("content:1");
            verify(jedis, never()).setex(eq("content:1"), anyLong(), anyString());
        }
    }

    @Test
    void getRedisErrorAndLoaderDbFailureReturnsNullWithoutWriteBack() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);

            assertNull(cache.get("content:1", SampleDto.class,
                    () -> { throw new DatabaseException("db down"); }, 100));

            // 降级路径：DB 加载失败转 null（不写回，D4）
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
    void getBatchLoaderDbFailureOnOneKeyReturnsNullForThatKeyWithoutEmptyMarker() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            Response<Boolean> notEmpty = boolResponse(false);
            Response<String> noJson = strResponse(null);
            when(p.exists(anyString())).thenReturn(notEmpty);
            when(p.get(anyString())).thenReturn(noJson);

            Map<String, SampleDto> result = cache.getBatch(
                    List.of("k1", "k2"), SampleDto.class,
                    k -> {
                        if (k.equals("k2")) {
                            throw new DatabaseException("db down");
                        }
                        return new SampleDto(1L, "one");
                    }, 100);

            assertEquals("one", result.get("k1").getName());
            assertNull(result.get("k2")); // 该 key 加载失败 → null（逐 key 优雅降级，不拖垮整批）
            // 失败的 k2 不写空标记、不 DEL 数据 key
            verify(jedis, never()).setex(eq("empty:k2"), anyLong(), anyString());
            verify(jedis, never()).del("k2");
            verify(jedis).setex(eq("k1"), anyLong(), anyString());
        }
    }

    @Test
    void getBatchRedisErrorAndLoaderDbFailureReturnsAllNullWithoutWriteBack() {
        CacheAside degradedCache = newDegradedCache();

        Map<String, SampleDto> result = degradedCache.getBatch(
                List.of("k1", "k2"), SampleDto.class,
                k -> { throw new DatabaseException("db down"); }, 100);

        // 整批 Redis 降级 + DB 加载失败 → 逐 key 转 null（不 500、不拖垮整批；降级路径不写回）
        assertNull(result.get("k1"));
        assertNull(result.get("k2"));
    }

    @Test
    void getBatchDirtyJsonLoaderDbFailureReturnsNullForThatKey() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline p = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(p);
            // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing）
            Response<Boolean> notEmpty = boolResponse(false);
            Response<String> badJson = strResponse("{not-json");
            Response<String> noJson = strResponse(null);
            when(p.exists(anyString())).thenReturn(notEmpty);
            when(p.get("k1")).thenReturn(badJson);
            when(p.get("k2")).thenReturn(noJson);

            Map<String, SampleDto> result = cache.getBatch(
                    List.of("k1", "k2"), SampleDto.class,
                    k -> {
                        if (k.equals("k1")) {
                            throw new DatabaseException("db down");
                        }
                        return new SampleDto(1L, "one");
                    }, 100);

            // k1 脏 JSON → 单 key 降级 → DB 加载失败 → null（不写空标记、不 DEL）；k2 miss 正常回填
            assertNull(result.get("k1"));
            assertEquals("one", result.get("k2").getName());
            verify(jedis, never()).setex(eq("empty:k1"), anyLong(), anyString());
            verify(jedis, never()).del("k1");
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
            // 三期 T4/N3：markEmpty 含存在守卫（数据 key 不存在→写空标记），del 已随守卫移除
            when(jedis.exists("k1")).thenReturn(false);

            Map<String, SampleDto> result = cache.getBatch(List.of("k1"), SampleDto.class,
                    k -> null, 100);

            assertNull(result.get("k1"));
            verify(jedis).setex("empty:k1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).del(anyString());
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
    void markEmptyWritesEmptyMarkerWhenDataKeyAbsent() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // 三期 T4/N3：存在守卫——数据 key 不存在才写空标记（del 已随守卫移除）
            when(jedis.exists("content:1")).thenReturn(false);

            cache.markEmpty("content:1");

            verify(jedis).setex("empty:content:1", CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                    CacheKeys.EMPTY_MARKER_VALUE);
            verify(jedis, never()).del(anyString());
        }
    }

    @Test
    void markEmptySkipsWhenDataKeyExists() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // 三期 T4/N3：数据 key 已存在（并发回填/业务写刚写入真数据）→ 不写空标记、不 DEL
            when(jedis.exists("content:1")).thenReturn(true);

            cache.markEmpty("content:1");

            verify(jedis, never()).setex(startsWith("empty:"), anyLong(), anyString());
            verify(jedis, never()).del(anyString());
        }
    }

    @Test
    void markEmptyExistsFailureSkipsQuietly() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            // 守卫检查失败（Redis 抖动）= 保守不写：宁可少写空标记（多一次 DB 查），绝不误写（假空）
            when(jedis.exists(anyString())).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> cache.markEmpty("content:1"));

            verify(jedis, never()).setex(startsWith("empty:"), anyLong(), anyString());
        }
    }

    /**
     * 三期 T4/N3 验收：并发"业务写真数据 + miss 回填写空标记"不产生假空。
     * 时序确定性：线程 A 的 loader 阻塞到线程 B 写入完成才返回 null，
     * 故 markEmpty 的 exists 守卫必然看到数据 key 已存在 → 跳过写空标记。
     * 注意：MockedStatic 线程局部不可跨线程（T2 教训），用 mock RedisAccess 直通共享 mock Jedis。
     */
    @Test
    void concurrentBackfillAndWriteNoFakeEmpty() throws Exception {
        RedisAccess sharedRedis = mock(RedisAccess.class);
        Jedis jedis = mock(Jedis.class);
        when(sharedRedis.execute(any(Function.class))).thenAnswer(inv -> {
            Function<Jedis, Object> fn = inv.getArgument(0);
            return fn.apply(jedis);
        });
        doAnswer(inv -> {
            Consumer<Jedis> c = inv.getArgument(0);
            c.accept(jedis);
            return null;
        }).when(sharedRedis).executeVoid(any(Consumer.class));
        CacheAside shared = new CacheAside(sharedRedis, new JacksonCodec(),
                new SingleFlight(), new CacheStats());

        // 单趟 pipeline 探测桩（get 走 probeRenew）：miss 态
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> emptyResp = boolResponse(false);
        Response<String> jsonResp = strResponse(null);
        when(p.exists(anyString())).thenReturn(emptyResp);
        when(p.get(anyString())).thenReturn(jsonResp);

        // 内存态数据 key：writeOrInvalidate 的 setex 置 true，markEmpty 的 exists 读
        AtomicBoolean dataKeyExists = new AtomicBoolean(false);
        when(jedis.exists("content:1")).thenAnswer(inv -> dataKeyExists.get());
        when(jedis.setex(eq("content:1"), anyLong(), anyString())).thenAnswer(inv -> {
            dataKeyExists.set(true);
            return "OK";
        });

        CountDownLatch written = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 线程 B：业务写路径写入真数据（模拟评论/点赞提交后的缓存同步）
            Future<?> writer = pool.submit(() -> {
                shared.writeOrInvalidate("content:1", new SampleDto(1L, "real"), 100);
                written.countDown();
            });
            // 线程 A：miss 回填——loader 等 B 写完才返回 null（读到"无数据"，回填时真数据已写入）
            Future<SampleDto> reader = pool.submit(() -> shared.get("content:1", SampleDto.class, () -> {
                written.await();
                return null;
            }, 100));

            assertNull(reader.get(5, TimeUnit.SECONDS));
            writer.get(5, TimeUnit.SECONDS);

            // 守卫生效：无假空（空标记未写）、真数据保留（数据 key 未被 DEL）；
            // exists 断言钉死 miss→markEmpty 守卫路径确实执行（防降级路径"空洞通过"）
            verify(jedis, never()).setex(eq("empty:content:1"), anyLong(), anyString());
            verify(jedis, never()).del("content:1");
            verify(jedis).setex(eq("content:1"), anyLong(), anyString());
            verify(jedis).exists("content:1");
        } finally {
            pool.shutdownNow();
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

    // ==================== 三期 T2：降级亦经单飞（同 key 并发只打一次 DB） ====================

    /** 并发测试专用：mock RedisAccess 恒抛 CacheException（MockedStatic 线程局部，多线程下不可用）。 */
    @SuppressWarnings("unchecked")
    private static CacheAside newDegradedCache() {
        RedisAccess down = mock(RedisAccess.class);
        when(down.execute(any())).thenThrow(new CacheException("redis down"));
        doThrow(new CacheException("redis down")).when(down).executeVoid(any());
        return new CacheAside(down, new JacksonCodec(), new SingleFlight(), new CacheStats());
    }

    @Test
    void getRedisErrorConcurrentSameKeyLoadsLoaderOnce() throws Exception {
        CacheAside degradedCache = newDegradedCache();
        int threads = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<SampleDto>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> degradedCache.get("content:1", SampleDto.class, () -> {
                    loads.incrementAndGet();
                    entered.countDown();
                    release.await();
                    return new SampleDto(1L, "db");
                }, 100)));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // 等其余线程全部进入单飞等待（对齐 SingleFlightTest 放大并发窗口）
            release.countDown();
            for (Future<SampleDto> f : futures) {
                assertEquals(1L, f.get(5, TimeUnit.SECONDS).getId());
            }
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getRedisErrorLoaderFailureNotSharedAndRetriedNextCall() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            stubRedisDown(jedis);
            AtomicInteger calls = new AtomicInteger();

            // 降级 loader 失败：异常上抛（失败不以数据形式共享）、条目移除
            assertThrows(RuntimeException.class, () -> cache.get("content:1", SampleDto.class, () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("db down");
            }, 100));

            // SingleFlight 失败清理语义保持：下一次降级全新重试 loader
            SampleDto value = cache.get("content:1", SampleDto.class, () -> {
                calls.incrementAndGet();
                return new SampleDto(1L, "db");
            }, 100);
            assertEquals("db", value.getName());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void getBatchRedisErrorConcurrentPerKeyLoadsOnce() throws Exception {
        CacheAside degradedCache = newDegradedCache();
        int threads = 8;
        Map<String, AtomicInteger> loads = Map.of("k1", new AtomicInteger(), "k2", new AtomicInteger());
        CountDownLatch enteredK1 = new CountDownLatch(1);
        CountDownLatch enteredK2 = new CountDownLatch(1);
        CountDownLatch releaseK1 = new CountDownLatch(1);
        CountDownLatch releaseK2 = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, SampleDto>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> degradedCache.getBatch(List.of("k1", "k2"), SampleDto.class, k -> {
                    loads.get(k).incrementAndGet();
                    (k.equals("k1") ? enteredK1 : enteredK2).countDown();
                    try {
                        (k.equals("k1") ? releaseK1 : releaseK2).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new SampleDto(9L, "loader-" + k);
                }, 100)));
            }
            // 降级路径逐 key 顺序单飞：放行 k1 → 全员汇合到 k2 → 放行 k2
            assertTrue(enteredK1.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // 等其余线程全部进入 k1 单飞等待（对齐 SingleFlightTest 放大并发窗口）
            releaseK1.countDown();
            assertTrue(enteredK2.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // 等其余线程全部进入 k2 单飞等待
            releaseK2.countDown();
            for (Future<Map<String, SampleDto>> f : futures) {
                Map<String, SampleDto> r = f.get(5, TimeUnit.SECONDS);
                assertEquals("loader-k1", r.get("k1").getName());
                assertEquals("loader-k2", r.get("k2").getName());
            }
            assertEquals(1, loads.get("k1").get());
            assertEquals(1, loads.get("k2").get());
        } finally {
            pool.shutdownNow();
        }
    }
}