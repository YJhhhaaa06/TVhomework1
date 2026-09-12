package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import com.itheima.util.MyRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheAsideTest {

    /** 真实组件组合 + mock MyRedisPool/Jedis（不碰真实 Redis，沿用 LikeCacheServiceTest 范式）。 */
    private final CacheAside cache = new CacheAside(new RedisAccess(), new JacksonCodec(),
            new SingleFlight());

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

    // ==================== read() 三态 ====================

    @Test
    void readHitEmptyReturnsHitEmptyWithoutReadingData() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("empty:content:1")).thenReturn(true);

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
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(new JacksonCodec().toJson(new SampleDto(1L, "alice")));

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.HIT_DATA, result.status());
            assertEquals(1L, result.value().getId());
            assertEquals("alice", result.value().getName());
        }
    }

    @Test
    void readMissWhenNoKeysExist() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(null);

            CacheResult<SampleDto> result = cache.read("content:1", SampleDto.class);

            assertEquals(CacheStatus.MISS, result.status());
            assertNull(result.value());
        }
    }

    @Test
    void readRedisErrorDegradesToMissWithoutThrowing() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("empty:content:1")).thenThrow(new RuntimeException("redis down"));

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
            when(jedis.exists("empty:content:1")).thenReturn(true);
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
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(new JacksonCodec().toJson(new SampleDto(1L, "alice")));
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
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(null);
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
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(null);

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
            when(jedis.exists("empty:content:comments:1")).thenReturn(false);
            when(jedis.get("content:comments:1")).thenReturn(null);
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
            when(jedis.exists("empty:content:1")).thenThrow(new RuntimeException("redis down"));
            SampleDto dto = new SampleDto(1L, "db");

            SampleDto value = cache.get("content:1", SampleDto.class, () -> dto, 100);

            assertSame(dto, value);
            verify(jedis, never()).setex(anyString(), anyLong(), anyString());
            verify(jedis, never()).del(anyString());
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
            // 前置：loader 无数据时写过空标记（评审[重要]项：写真实数据必须清空标记，否则读路径最长 60s 假空）
            when(jedis.exists("empty:content:1")).thenReturn(true);

            cache.writeOrInvalidate("content:1", new SampleDto(1L, "alice"), 100);

            verify(jedis).del("empty:content:1");

            // 写数据后再读：空标记已清，读路径不再返回假空
            when(jedis.exists("empty:content:1")).thenReturn(false);
            when(jedis.get("content:1")).thenReturn(new JacksonCodec().toJson(new SampleDto(1L, "alice")));
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
}