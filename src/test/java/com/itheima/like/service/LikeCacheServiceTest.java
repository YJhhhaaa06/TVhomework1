package com.itheima.like.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SetCache;
import com.itheima.cache.SingleFlight;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * T4 点赞缓存（计数/成员分离 + 单飞 + 降级）单测。
 * 第四期 T2 起：Set 成员读路径收口 SetCache（同一 mock redis + 真实 SingleFlight + 同一 stats
 * 组合注入），用例桩透明平移；风格对齐 CommentCacheTest：mock DAO/RedisAccess/CacheAside + tt 跑 loader；
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
        // 第四期 T2：读路径收口 SetCache（复用同一 mock redis / 真实 SingleFlight / 同一 stats，
        // 保证既有降级并发与统计断言不因组件平移而破）
        SetCache setCache = new SetCache(redis, cacheAside, new SingleFlight(), stats);
        service = new LikeCacheService(contentLikeDao, commentLikeDao, tt, redis,
                setCache, cacheAside, stats);

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

    // ==================== 写路径：内容点赞（Lua 原子条件写 + 失败失效，三期 T4/N7） ====================

    @Test
    void likeContentRunsConditionalLuaScript() {
        // 三期 T4/N7：条件语义内聚 EVAL 脚本（Redis 服务端原子执行），单测验证调用参数
        service.likeContent(7L, 1L);

        verify(jedis).eval(eq(LikeCacheService.LIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.contentLikeSet(1L), CacheKeys.contentLikeCount(1L),
                        CacheKeys.empty(CacheKeys.contentLikeSet(1L)))),
                eq(List.of("7")));
    }

    @Test
    void unlikeContentRunsConditionalLuaScript() {
        service.unlikeContent(7L, 1L);

        verify(jedis).eval(eq(LikeCacheService.UNLIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.contentLikeSet(1L), CacheKeys.contentLikeCount(1L))),
                eq(List.of("7")));
    }

    @Test
    void likeContentFailureInvalidatesKeys() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.likeContent(7L, 1L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(1L), CacheKeys.contentLikeSet(1L));
    }

    @Test
    void unlikeContentFailureInvalidatesKeys() {
        // 三期 T4/N7 评审补齐：unlike 失效降级路径对称覆盖
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.unlikeContent(7L, 1L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(1L), CacheKeys.contentLikeSet(1L));
    }

    @Test
    void unlikeCommentFailureInvalidatesKeys() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.unlikeComment(7L, 9L);

        verify(cacheAside).invalidate(CacheKeys.commentLikeCount(9L), CacheKeys.commentLikeSet(9L));
    }

    @Test
    void likeCommentRunsConditionalLuaScript() {
        // 三期 T4/N7 对称补测（comment 版写路径此前零覆盖）
        service.likeComment(7L, 9L);

        verify(jedis).eval(eq(LikeCacheService.LIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.commentLikeSet(9L), CacheKeys.commentLikeCount(9L),
                        CacheKeys.empty(CacheKeys.commentLikeSet(9L)))),
                eq(List.of("7")));
    }

    @Test
    void unlikeCommentRunsConditionalLuaScript() {
        service.unlikeComment(7L, 9L);

        verify(jedis).eval(eq(LikeCacheService.UNLIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.commentLikeSet(9L), CacheKeys.commentLikeCount(9L))),
                eq(List.of("7")));
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
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Set.of(7L));

        assertTrue(service.isContentLiked(7L, 1L));
        // 三期 T2：降级经单飞全量装载作答（替代原单行查询），且不写回
        verify(contentLikeDao).findLikerIdsByContentId(conn, 1L);
        verify(contentLikeDao, never()).isLiked(any(), anyLong(), anyLong());
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void isContentLikedRedisErrorConcurrentDegradeLoadsDbOnce() throws Exception {
        // 三期 T2 验收：Redis 停机时同一 key 的并发读只触发一次 DB 装载
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        int threads = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenAnswer(inv -> {
            loads.incrementAndGet();
            entered.countDown();
            release.await();
            return Set.of(7L);
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> service.isContentLiked(7L, 1L)));
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
    void batchIsContentLikedRedisErrorDegradesFromDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Set.of(7L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L));

        assertEquals(true, result.get(1L));
        // 三期 T2：降级经单飞全量装载作答——不再走 targeted 批量查询，也不尝试回填写入
        verify(contentLikeDao).findLikerIdsByContentId(conn, 1L);
        verify(contentLikeDao, never()).findLikedContentIds(any(), anyLong(), anyList());
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void batchIsCommentLikedEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
    }

    // ==================== 批量回填 best-effort（第四期 T2 收口 SetCache 后的 L2 差异对照） ====================

    /**
     * T1 登记差异（TASKS T1 执行回写）：收口前批量回填 loader DB 失败上抛（批量 500）；
     * 收口后 SetCache 契约 = best-effort——DB 答案照常返回，仅缓存受影响（4.2）。
     */
    @Test
    void batchIsContentLikedBackfillLoaderFailureIsBestEffort() throws SQLException {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        // id=3 miss（三态桩）
        Response<Boolean> empty3 = booleanResponse(false);
        Response<Boolean> exists3 = booleanResponse(false);
        Response<Boolean> member3 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(CacheKeys.contentLikeSet(3L)))).thenReturn(empty3);
        when(p.exists(CacheKeys.contentLikeSet(3L))).thenReturn(exists3);
        when(p.sismember(CacheKeys.contentLikeSet(3L), "7")).thenReturn(member3);
        // dbAnswer 成立（DB 即真理）：用户点赞过 id=3
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(3L))).thenReturn(Set.of(3L));
        // 回填全量成员 loader 真失败（DB 故障）
        when(contentLikeDao.findLikerIdsByContentId(conn, 3L)).thenThrow(new SQLException("db down"));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(3L));

        // 不抛；DB 答案照常返回（缓存失败不得导致业务失败，4.2）
        assertEquals(true, result.get(3L));
    }

    /**
     * 单成员 miss 装载 loader DB 失败仍上抛（与收口前一致，无行为漂移）——
     * SetCache.isMember 仅 catch CacheException，真实 DB 失败向上抛。
     */
    @Test
    void isContentLikedMissLoaderDbFailureStillThrows() throws SQLException {
        stubSetScan(1L, false, false, null);
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.isContentLiked(7L, 1L));
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