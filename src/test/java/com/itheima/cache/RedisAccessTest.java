package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.util.MyRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RedisAccessTest {

    private final RedisAccess redis = new RedisAccess();

    private static Jedis mockJedis(MockedStatic<MyRedisPool> ms) {
        Jedis jedis = mock(Jedis.class);
        ms.when(() -> MyRedisPool.getJedis()).thenReturn(jedis);
        return jedis;
    }

    @Test
    void executeReturnsResultAndClosesJedis() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("content:1")).thenReturn("{\"id\":1}");

            String result = redis.execute(j -> j.get("content:1"));

            assertEquals("{\"id\":1}", result);
            verify(jedis).get("content:1");
            verify(jedis).close();
        }
    }

    @Test
    void executeVoidRunsConsumerAndClosesJedis() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            redis.executeVoid(j -> j.set("content:1", "v"));

            verify(jedis).set("content:1", "v");
            verify(jedis).close();
        }
    }

    @Test
    void executeWrapsRedisErrorIntoCacheException() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("content:1")).thenThrow(new RuntimeException("redis down"));

            assertThrows(CacheException.class, () -> redis.execute(j -> j.get("content:1")));
            verify(jedis).close();
        }
    }

    @Test
    void executeWrapsPoolAcquireErrorIntoCacheException() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            ms.when(() -> MyRedisPool.getJedis()).thenThrow(new RuntimeException("pool exhausted"));

            assertThrows(CacheException.class, () -> redis.execute(j -> j.get("content:1")));
        }
    }

    // ==================== T1（cache-01）熔断接线 ====================

    /** 小阈值/长冷却熔断器（冷却 60s，避免测试抖动期偶然探测）。 */
    private static RedisAccess accessWithBreaker(int threshold) {
        return new RedisAccess(new RedisCircuitBreaker(threshold, 60_000L));
    }

    @Test
    void breakerOpenFailsFastWithoutTouchingPool() {
        RedisAccess access = accessWithBreaker(2);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("content:1")).thenThrow(new RuntimeException("redis down"));

            // 两次真实失败 → 熔断开启
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("content:1")));
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("content:1")));

            // 熔断开启：快速失败，且从未再取连接
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("content:1")));
            verify(jedis, times(2)).get("content:1"); // 第三次请求未触达 Jedis
            verify(jedis, times(2)).close();
        }
    }

    @Test
    void consecutiveFailuresOpenCircuitThenDeny() {
        RedisAccess access = accessWithBreaker(2);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            ms.when(() -> MyRedisPool.getJedis()).thenThrow(new RuntimeException("pool exhausted"));

            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k"))); // 达阈值 → OPEN
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k"))); // 熔断拒绝
        }
    }

    @Test
    void successBetweenFailuresPreventsCircuitOpen() {
        RedisAccess access = accessWithBreaker(2);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("k")).thenThrow(new RuntimeException("redis down"))
                    .thenReturn("v") // 成功清零连续计数
                    .thenThrow(new RuntimeException("redis down"))
                    .thenReturn("v2");

            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            assertEquals("v", access.execute(j -> j.get("k")));
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            // 仅 1 次连续失败 < 阈值 2 → 仍 CLOSED，第 4 次仍放行访问 Redis（未熔断）
            assertEquals("v2", access.execute(j -> j.get("k")));
            verify(jedis, times(4)).get("k");
        }
    }

    @Test
    void probeAfterCooldownRecoversThroughExecute() throws InterruptedException {
        // 短冷却（300ms）驱动"熔断开启 → 冷却期满 → 探针成功 → 恢复 CLOSED"全链路（经 execute 接线）
        RedisAccess access = new RedisAccess(new RedisCircuitBreaker(2, 300L));
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("k")).thenThrow(new RuntimeException("redis down"))
                    .thenThrow(new RuntimeException("redis down"))
                    .thenReturn("v") // 探针成功
                    .thenReturn("v2");

            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k"))); // → OPEN
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k"))); // 冷却期内拒绝

            Thread.sleep(450); // 冷却期满
            assertEquals("v", access.execute(j -> j.get("k"))); // 探针放行且成功 → CLOSED
            assertEquals("v2", access.execute(j -> j.get("k"))); // 恢复后正常放行
            verify(jedis, times(4)).get("k");
        }
    }

    @Test
    void callbackCacheExceptionCountsAsBreakerFailure() {
        // 口径钉死：从 execute 冒出的 CacheException（含回调内自抛）计一次熔断失败
        RedisAccess access = accessWithBreaker(2);
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.get("k")).thenThrow(new CacheException("boom"));

            CacheException e1 = assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            assertEquals("boom", e1.getMessage()); // 原样上抛，非熔断文案
            assertThrows(CacheException.class, () -> access.execute(j -> j.get("k"))); // 计满 → OPEN

            CacheException e3 = assertThrows(CacheException.class, () -> access.execute(j -> j.get("k")));
            assertEquals("Redis 熔断开启中，快速失败（未访问 Redis）", e3.getMessage()); // 第 3 次被熔断拒绝
            verify(jedis, times(2)).get("k"); // 第 3 次未触达 Jedis
        }
    }
}