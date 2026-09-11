package com.itheima.like.service;

import com.itheima.util.MyRedisPool;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeCacheServiceTest {

    private final LikeCacheService service = new LikeCacheService();

    private Jedis mockJedis(MockedStatic<MyRedisPool> ms) {
        Jedis jedis = mock(Jedis.class);
        ms.when(() -> MyRedisPool.getJedis()).thenReturn(jedis);
        return jedis;
    }

    @Test
    void likeContentAddsUserToContentSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.likeContent(7L, 1L);

            verify(jedis).sadd("content:like:1", "7");
        }
    }

    @Test
    void unlikeContentRemovesUserFromContentSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.unlikeContent(7L, 1L);

            verify(jedis).srem("content:like:1", "7");
        }
    }

    @Test
    void likeCommentAddsUserToCommentSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.likeComment(7L, 9L);

            verify(jedis).sadd("comment:like:9", "7");
        }
    }

    @Test
    void unlikeCommentRemovesUserFromCommentSet() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.unlikeComment(7L, 9L);

            verify(jedis).srem("comment:like:9", "7");
        }
    }

    @Test
    void isContentLikedCacheMissReturnsNull() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("content:like:1")).thenReturn(false);

            assertNull(service.isContentLiked(7L, 1L));
        }
    }

    @Test
    void isContentLikedTrueWhenMember() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("content:like:1")).thenReturn(true);
            when(jedis.sismember("content:like:1", "7")).thenReturn(true);

            assertEquals(Boolean.TRUE, service.isContentLiked(7L, 1L));
        }
    }

    @Test
    void isContentLikedFalseWhenNotMember() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("content:like:1")).thenReturn(true);
            when(jedis.sismember("content:like:1", "7")).thenReturn(false);

            assertEquals(Boolean.FALSE, service.isContentLiked(7L, 1L));
        }
    }

    @Test
    void getContentLikeCountCacheMissReturnsNull() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("content:like:1")).thenReturn(false);

            assertNull(service.getContentLikeCount(1L));
        }
    }

    @Test
    void getContentLikeCountReturnsScard() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("content:like:1")).thenReturn(true);
            when(jedis.scard("content:like:1")).thenReturn(3L);

            assertEquals(3, service.getContentLikeCount(1L));
        }
    }

    @Test
    void isCommentLikedTrueWhenMember() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("comment:like:9")).thenReturn(true);
            when(jedis.sismember("comment:like:9", "7")).thenReturn(true);

            assertEquals(Boolean.TRUE, service.isCommentLiked(7L, 9L));
        }
    }

    @Test
    void getCommentLikeCountReturnsScard() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            when(jedis.exists("comment:like:9")).thenReturn(true);
            when(jedis.scard("comment:like:9")).thenReturn(5L);

            assertEquals(5, service.getCommentLikeCount(9L));
        }
    }

    @Test
    void batchIsContentLikedEmptyInputSkipsRedis() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Map<Long, Boolean> result = service.batchIsContentLiked(7L, Collections.emptyList());

            assertTrue(result.isEmpty());
            ms.verify(() -> MyRedisPool.getJedis(), never());
        }
    }

    @Test
    void batchIsContentLikedOnlyIncludesExistingKeys() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline pipeline = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(pipeline);
            Response<Boolean> exist1 = mock(Response.class);
            Response<Boolean> exist2 = mock(Response.class);
            Response<Boolean> exist3 = mock(Response.class);
            Response<Boolean> member1 = mock(Response.class);
            Response<Boolean> member2 = mock(Response.class);
            when(pipeline.exists("content:like:1")).thenReturn(exist1);
            when(pipeline.exists("content:like:2")).thenReturn(exist2);
            when(pipeline.exists("content:like:3")).thenReturn(exist3);
            when(pipeline.sismember("content:like:1", "7")).thenReturn(member1);
            when(pipeline.sismember("content:like:2", "7")).thenReturn(member2);
            when(exist1.get()).thenReturn(true);
            when(exist2.get()).thenReturn(true);
            when(exist3.get()).thenReturn(false);
            when(member1.get()).thenReturn(true);
            when(member2.get()).thenReturn(false);

            Map<Long, Boolean> result = service.batchIsContentLiked(7L, java.util.List.of(1L, 2L, 3L));

            assertEquals(2, result.size());
            assertEquals(true, result.get(1L));
            assertEquals(false, result.get(2L));
            assertFalse(result.containsKey(3L));
            verify(pipeline).sync();
        }
    }

    @Test
    void batchIsCommentLikedOnlyIncludesExistingKeys() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);
            Pipeline pipeline = mock(Pipeline.class);
            when(jedis.pipelined()).thenReturn(pipeline);
            Response<Boolean> exist1 = mock(Response.class);
            Response<Boolean> exist2 = mock(Response.class);
            Response<Boolean> member1 = mock(Response.class);
            when(pipeline.exists("comment:like:9")).thenReturn(exist1);
            when(pipeline.exists("comment:like:10")).thenReturn(exist2);
            when(pipeline.sismember("comment:like:9", "7")).thenReturn(member1);
            when(exist1.get()).thenReturn(true);
            when(exist2.get()).thenReturn(false);
            when(member1.get()).thenReturn(true);

            Map<Long, Boolean> result = service.batchIsCommentLiked(7L, java.util.List.of(9L, 10L));

            assertEquals(1, result.size());
            assertEquals(true, result.get(9L));
            assertFalse(result.containsKey(10L));
        }
    }

    @Test
    void syncContentLikersEmptySetMarksPlaceholder() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.syncContentLikers(1L, Collections.emptySet());

            verify(jedis).sadd("content:like:1", "__placeholder__");
            verify(jedis).srem("content:like:1", "__placeholder__");
        }
    }

    @Test
    void syncContentLikersNonEmptyAddsAllMembers() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.syncContentLikers(1L, Set.of(7L, 8L));

            // 集合无序，仅断言 key 与成员数组
            verify(jedis).sadd(eq("content:like:1"), any(String[].class));
        }
    }

    @Test
    void syncCommentLikersEmptySetMarksPlaceholder() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.syncCommentLikers(9L, Collections.emptySet());

            verify(jedis).sadd("comment:like:9", "__placeholder__");
            verify(jedis).srem("comment:like:9", "__placeholder__");
        }
    }

    @Test
    void deleteMethodsDeleteKeys() {
        try (MockedStatic<MyRedisPool> ms = mockStatic(MyRedisPool.class)) {
            Jedis jedis = mockJedis(ms);

            service.deleteContentLike(5L);
            verify(jedis).del("content:like:5");

            // 现状行为：deleteCommentLike 用的是 contentLikeKey（技术债，只测现状不改）
            service.deleteCommentLike(9L);
            verify(jedis).del("content:like:9");
        }
    }
}