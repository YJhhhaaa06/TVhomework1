package com.itheima.like.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.like.dao.CommentLikeDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T4 点赞缓存（计数/成员分离 + 单飞 + 降级）单测。
 * 风格对齐 CommentCacheTest：mock DAO/RedisAccess/CacheAside + 真实 SingleFlight + tt 跑 loader；
 * 通过 Mockito 令 RedisAccess.execute/Void 作用于 mock Jedis（含 pipeline），可验证 key 与写命令。
 */
class LikeCacheServiceTest {

    private ContentLikeDao contentLikeDao;
    private CommentLikeDao commentLikeDao;
    private TransactionTemplate tt;
    private Connection conn;
    private RedisAccess redis;
    private CacheAside cacheAside;
    private Jedis jedis;
    private LikeCacheService service;
    private CacheStats stats;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        contentLikeDao = mock(ContentLikeDao.class);
        commentLikeDao = mock(CommentLikeDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        redis = mock(RedisAccess.class);
        cacheAside = mock(CacheAside.class);
        jedis = mock(Jedis.class);
        stats = new CacheStats();
        service = new LikeCacheService(contentLikeDao, commentLikeDao, tt, redis,
                new SingleFlight(), cacheAside, stats);

        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
        when(redis.execute(any(Function.class))).thenAnswer(inv -> {
            Function<Jedis, Object> fn = inv.getArgument(0);
            return fn.apply(jedis);
        });
        doAnswer(inv -> {
            Consumer<Jedis> c = inv.getArgument(0);
            c.accept(jedis);
            return null;
        }).when(redis).executeVoid(any(Consumer.class));
    }

    // ==================== 工具 ====================

    /** Response mock：注意必须在 when(...) 之前创建完毕，避免 thenReturn 内嵌 stubbing（UnfinishedStubbing）。 */
    private Response<Boolean> booleanResponse(Boolean v) {
        Response<Boolean> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    /** 令单条三态扫描（scanLikeSet）命中指定 [空标记, set 存在, 成员] 组合。 */
    private void stubSetScan(long contentId, Boolean empty, Boolean exists, Boolean member) {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Boolean> memberResp = booleanResponse(member);
        when(p.exists(CacheKeys.empty(CacheKeys.contentLikeSet(contentId)))).thenReturn(emptyResp);
        when(p.exists(CacheKeys.contentLikeSet(contentId))).thenReturn(existsResp);
        when(p.sismember(CacheKeys.contentLikeSet(contentId), "7")).thenReturn(memberResp);
    }

    // ==================== 写路径：内容点赞（条件写 + 清空标记 + 失败失效） ====================

    @Test
    void likeContentClearsEmptyMarkerAndConditionalWrites() {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> setExists = booleanResponse(true);
        Response<Boolean> countExists = booleanResponse(true);
        when(p.exists(CacheKeys.contentLikeSet(1L))).thenReturn(setExists);
        when(p.exists(CacheKeys.contentLikeCount(1L))).thenReturn(countExists);

        service.likeContent(7L, 1L);

        verify(p).del(CacheKeys.empty(CacheKeys.contentLikeSet(1L)));
        verify(jedis).sadd(CacheKeys.contentLikeSet(1L), "7");
        verify(jedis).incr(CacheKeys.contentLikeCount(1L));
    }

    @Test
    void likeContentSkipsWhenKeysAbsent() {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> setExists = booleanResponse(false);
        Response<Boolean> countExists = booleanResponse(false);
        when(p.exists(CacheKeys.contentLikeSet(1L))).thenReturn(setExists);
        when(p.exists(CacheKeys.contentLikeCount(1L))).thenReturn(countExists);

        service.likeContent(7L, 1L);

        verify(jedis, never()).sadd(anyString(), anyString());
        verify(jedis, never()).incr(anyString());
        verify(p).del(CacheKeys.empty(CacheKeys.contentLikeSet(1L)));
    }

    @Test
    void likeContentFailureInvalidatesKeys() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.likeContent(7L, 1L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(1L), CacheKeys.contentLikeSet(1L));
    }

    @Test
    void unlikeContentConditionallySremAndDecr() {
        when(jedis.exists(CacheKeys.contentLikeSet(1L))).thenReturn(true);
        when(jedis.exists(CacheKeys.contentLikeCount(1L))).thenReturn(true);

        service.unlikeContent(7L, 1L);

        verify(jedis).srem(CacheKeys.contentLikeSet(1L), "7");
        verify(jedis).decr(CacheKeys.contentLikeCount(1L));
    }

    // ==================== 读路径：内容点赞成员三态 ====================

    @Test
    void isContentLikedEmptyMarkerReturnsFalseWithoutDb() {
        stubSetScan(1L, true, null, null);

        assertFalse(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void isContentLikedEmptyMarkerRecordsHitEmptyToLikeDomain() {
        stubSetScan(1L, true, null, null);

        assertFalse(service.isContentLiked(7L, 1L));

        assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_EMPTY));
        assertEquals(0, stats.count(CacheDomain.LIKE, CacheStats.Event.MISS));
    }

    @Test
    void isContentLikedTrueWhenMember() {
        stubSetScan(1L, false, true, true);

        assertTrue(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void isContentLikedFalseWhenSetHasNoMember() {
        stubSetScan(1L, false, true, false);

        assertFalse(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    // ==================== T9 滑动续期：命中 set 顺带续期、空标记不续 ====================

    @Test
    void isContentLikedHitDataRenewsSetTtlButNotEmptyMarker() {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.contentLikeSet(1L);
        // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing，同文件惯例）
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> memberResp = booleanResponse(true);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "7")).thenReturn(memberResp);

        assertTrue(service.isContentLiked(7L, 1L));

        // hit-data：set key 续期（值=域 TTL，与写路径 expire 口径一致；读配置 getter 防分域调参断挂）
        verify(p).expire(setKey, AppConfig.getLikeTtlSeconds());
        // 空标记 key 从不被续期
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void isContentLikedMissBackfillsSetAndReturnsContains() throws SQLException {
        stubSetScan(1L, false, false, null);
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Set.of(7L, 8L));

        assertTrue(service.isContentLiked(7L, 1L));
        verify(jedis).sadd(eq(CacheKeys.contentLikeSet(1L)), any(String[].class));
        verify(jedis).expire(eq(CacheKeys.contentLikeSet(1L)), anyLong());
        verify(cacheAside, never()).markEmpty(anyString());
    }

    @Test
    void isContentLikedMissEmptyLikersWritesEmptyMarker() throws SQLException {
        stubSetScan(1L, false, false, null);
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Collections.emptySet());

        assertFalse(service.isContentLiked(7L, 1L));
        verify(cacheAside).markEmpty(CacheKeys.contentLikeSet(1L));
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void isContentLikedRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(true);

        assertTrue(service.isContentLiked(7L, 1L));
        verify(contentLikeDao).isLiked(conn, 7L, 1L);
    }

    // ==================== 读路径：内容/评论点赞计数（CacheAside loader） ====================

    @Test
    @SuppressWarnings("unchecked")
    void getContentLikeCountRunsLoaderThroughCacheAside() throws SQLException {
        when(contentLikeDao.countByContentId(conn, 1L)).thenReturn(5);
        stubCacheAsideGetToRunLoader();

        assertEquals(5, service.getContentLikeCount(1L));
        verify(cacheAside).get(eq(CacheKeys.contentLikeCount(1L)), eq(Integer.class),
                any(Callable.class), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getContentLikeCountZeroIsValid() throws SQLException {
        when(contentLikeDao.countByContentId(conn, 1L)).thenReturn(0);
        stubCacheAsideGetToRunLoader();

        assertEquals(0, service.getContentLikeCount(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCommentLikeCountRunsLoaderThroughCacheAside() throws SQLException {
        when(commentLikeDao.countByCommentId(conn, 9L)).thenReturn(3);
        stubCacheAsideGetToRunLoader();

        assertEquals(3, service.getCommentLikeCount(9L));
        verify(cacheAside).get(eq(CacheKeys.commentLikeCount(9L)), eq(Integer.class),
                any(Callable.class), anyLong());
    }

    // ==================== 读路径：评论点赞成员（三态 miss 代表用例） ====================

    @Test
    void isCommentLikedMissBackfillsAndContains() throws SQLException {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> memberResp = booleanResponse(false);
        when(p.exists(CacheKeys.empty(CacheKeys.commentLikeSet(9L)))).thenReturn(emptyResp);
        when(p.exists(CacheKeys.commentLikeSet(9L))).thenReturn(existsResp);
        when(p.sismember(CacheKeys.commentLikeSet(9L), "7")).thenReturn(memberResp);
        when(commentLikeDao.findLikerIdsByCommentId(conn, 9L)).thenReturn(Set.of(7L));

        assertTrue(service.isCommentLiked(7L, 9L));
        verify(jedis).sadd(eq(CacheKeys.commentLikeSet(9L)), any(String[].class));
        verify(jedis).expire(eq(CacheKeys.commentLikeSet(9L)), anyLong());
    }

    // ==================== 批量 ====================

    @Test
    void batchIsContentLikedEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = service.batchIsContentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
    }

    @Test
    void batchIsContentLikedMixesHitsMissesAndBackfills() throws SQLException {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> empty1 = booleanResponse(true);   // id=1 空标记 → false
        Response<Boolean> empty2 = booleanResponse(false);
        Response<Boolean> empty3 = booleanResponse(false);
        Response<Boolean> set1 = booleanResponse(false);
        Response<Boolean> mem1 = booleanResponse(false);
        Response<Boolean> set2 = booleanResponse(true);     // id=2 命中成员 → true
        Response<Boolean> mem2 = booleanResponse(true);
        Response<Boolean> set3 = booleanResponse(false);    // id=3 缺失 → DB 兜底
        Response<Boolean> mem3 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(CacheKeys.contentLikeSet(1L)))).thenReturn(empty1);
        when(p.exists(CacheKeys.empty(CacheKeys.contentLikeSet(2L)))).thenReturn(empty2);
        when(p.exists(CacheKeys.empty(CacheKeys.contentLikeSet(3L)))).thenReturn(empty3);
        when(p.exists(CacheKeys.contentLikeSet(1L))).thenReturn(set1);
        when(p.sismember(CacheKeys.contentLikeSet(1L), "7")).thenReturn(mem1);
        when(p.exists(CacheKeys.contentLikeSet(2L))).thenReturn(set2);
        when(p.sismember(CacheKeys.contentLikeSet(2L), "7")).thenReturn(mem2);
        when(p.exists(CacheKeys.contentLikeSet(3L))).thenReturn(set3);
        when(p.sismember(CacheKeys.contentLikeSet(3L), "7")).thenReturn(mem3);
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(3L))).thenReturn(Set.of(3L));
        when(contentLikeDao.findLikerIdsByContentId(conn, 3L)).thenReturn(Set.of(3L, 7L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L, 3L));

        assertEquals(false, result.get(1L));
        assertEquals(true, result.get(2L));
        assertEquals(true, result.get(3L));
        verify(jedis).expire(eq(CacheKeys.contentLikeSet(3L)), anyLong());
    }

    @Test
    void batchIsContentLikedRedisErrorAllBackfilledFromDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(1L))).thenReturn(Set.of());
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Set.of(7L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L));

        assertEquals(false, result.get(1L));
        verify(contentLikeDao).findLikedContentIds(conn, 7L, List.of(1L));
    }

    @Test
    void batchIsCommentLikedEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
    }

    // ==================== 失效（内容/评论删除级联，4.5） ====================

    @Test
    void deleteContentLikeInvalidatesCountAndSet() {
        service.deleteContentLike(5L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(5L), CacheKeys.contentLikeSet(5L));
    }

    @Test
    void deleteCommentLikeInvalidatesCountAndSet() {
        service.deleteCommentLike(9L);

        verify(cacheAside).invalidate(CacheKeys.commentLikeCount(9L), CacheKeys.commentLikeSet(9L));
    }

    // ==================== 工具 ====================

    /** 令 cacheAside.get(...) 真实执行 loader（对齐 CommentCacheTest：三态语义由 CacheAsideTest 覆盖）。 */
    @SuppressWarnings("unchecked")
    private void stubCacheAsideGetToRunLoader() {
        when(cacheAside.get(anyString(), any(Class.class), any(Callable.class), anyLong()))
                .thenAnswer(inv -> {
                    Callable<Integer> loader = inv.getArgument(2);
                    try {
                        return loader.call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}