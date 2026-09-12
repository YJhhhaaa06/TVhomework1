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
}