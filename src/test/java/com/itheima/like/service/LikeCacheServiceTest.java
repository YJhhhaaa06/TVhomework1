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
 * 第四期 T4 起：成员 key 由内容/评论维度反转为用户维度（user:likeSet:{userId} / user:commentLikeSet:{userId}，
 * 判成员 = 用户维度 set 是否含内容/评论 id，loader = findXxxByUser 全量；批量收敛为单 set 多成员，
 * 失效仅计数 key）——用例随反转平移改写，验收锁反转语义。
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

    /** 令单条三态扫描（scanSet）命中指定 [空标记, set 存在, 成员] 组合（通用 setKey + member）。 */
    private void stubSetScan(String setKey, long memberId, Boolean empty, Boolean exists, Boolean member) {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Boolean> memberResp = booleanResponse(member);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, String.valueOf(memberId))).thenReturn(memberResp);
    }

    /** 内容成员三态扫描（T4 反转：user:likeSet:{userId} 判 contentId）。 */
    private void stubContentSetScan(long userId, long contentId, Boolean empty, Boolean exists, Boolean member) {
        stubSetScan(CacheKeys.userLikeSet(userId), contentId, empty, exists, member);
    }

    // ==================== 写路径：内容点赞（Lua 原子条件写 + 失败失效，三期 T4/N7；T4 反转 KEYS/ARGV） ====================

    @Test
    void likeContentRunsConditionalLuaScriptOnUserSet() {
        // T4 反转：成员写 user:likeSet:{userId}（SADD contentId），计数仍 content:likeCount:{contentId}（INCR）
        service.likeContent(7L, 1L);

        verify(jedis).eval(eq(LikeCacheService.LIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.userLikeSet(7L), CacheKeys.contentLikeCount(1L),
                        CacheKeys.empty(CacheKeys.userLikeSet(7L)))),
                eq(List.of("1")));
    }

    @Test
    void unlikeContentRunsConditionalLuaScriptOnUserSet() {
        service.unlikeContent(7L, 1L);

        verify(jedis).eval(eq(LikeCacheService.UNLIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.userLikeSet(7L), CacheKeys.contentLikeCount(1L))),
                eq(List.of("1")));
    }

    @Test
    void likeContentFailureInvalidatesCountKeyOnly() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.likeContent(7L, 1L);

        // T4 反转：成员 key 为用户维度，写失败仅失效 count key（成员残留由读自愈对齐用户全量）
        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(1L));
        verify(cacheAside, never()).invalidate(CacheKeys.userLikeSet(7L));
    }

    @Test
    void unlikeContentFailureInvalidatesCountKeyOnly() {
        // 三期 T4/N7 评审补齐：unlike 失效降级路径对称覆盖
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.unlikeContent(7L, 1L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(1L));
        verify(cacheAside, never()).invalidate(CacheKeys.userLikeSet(7L));
    }

    @Test
    void unlikeCommentFailureInvalidatesCountKeyOnly() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        service.unlikeComment(7L, 9L);

        verify(cacheAside).invalidate(CacheKeys.commentLikeCount(9L));
        verify(cacheAside, never()).invalidate(CacheKeys.userCommentLikeSet(7L));
    }

    @Test
    void likeCommentRunsConditionalLuaScriptOnUserSet() {
        // T4 反转：成员写 user:commentLikeSet:{userId}（SADD commentId）
        service.likeComment(7L, 9L);

        verify(jedis).eval(eq(LikeCacheService.LIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.userCommentLikeSet(7L), CacheKeys.commentLikeCount(9L),
                        CacheKeys.empty(CacheKeys.userCommentLikeSet(7L)))),
                eq(List.of("9")));
    }

    @Test
    void unlikeCommentRunsConditionalLuaScriptOnUserSet() {
        service.unlikeComment(7L, 9L);

        verify(jedis).eval(eq(LikeCacheService.UNLIKE_CONDITIONAL_SCRIPT),
                eq(List.of(CacheKeys.userCommentLikeSet(7L), CacheKeys.commentLikeCount(9L))),
                eq(List.of("9")));
    }

    // ==================== 读路径：内容点赞成员三态（T4 反转：user:likeSet 判 contentId） ====================

    @Test
    void isContentLikedEmptyMarkerReturnsFalseWithoutDb() {
        stubContentSetScan(7L, 1L, true, null, null);

        assertFalse(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void isContentLikedEmptyMarkerRecordsHitEmptyToLikeDomain() {
        stubContentSetScan(7L, 1L, true, null, null);

        assertFalse(service.isContentLiked(7L, 1L));

        assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_EMPTY));
        assertEquals(0, stats.count(CacheDomain.LIKE, CacheStats.Event.MISS));
    }

    @Test
    void isContentLikedTrueWhenMember() {
        stubContentSetScan(7L, 1L, false, true, true);

        assertTrue(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void isContentLikedFalseWhenSetHasNoMember() {
        stubContentSetScan(7L, 1L, false, true, false);

        assertFalse(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    // ==================== T9 滑动续期：命中 set 顺带续期、空标记不续 ====================

    @Test
    void isContentLikedHitDataRenewsSetTtlButNotEmptyMarker() {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userLikeSet(7L);
        // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing，同文件惯例）
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> memberResp = booleanResponse(true);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "1")).thenReturn(memberResp);

        assertTrue(service.isContentLiked(7L, 1L));

        // hit-data：set key 续期（值=域 TTL，与写路径 expire 口径一致；读配置 getter 防分域调参断挂）
        verify(p).expire(setKey, AppConfig.getLikeTtlSeconds());
        // 空标记 key 从不被续期
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void isContentLikedMissBackfillsUserSetAndReturnsContains() throws SQLException {
        stubContentSetScan(7L, 1L, false, false, null);
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenReturn(Set.of(1L, 8L));

        assertTrue(service.isContentLiked(7L, 1L));
        verify(jedis).sadd(eq(CacheKeys.userLikeSet(7L)), any(String[].class));
        verify(jedis).expire(eq(CacheKeys.userLikeSet(7L)), anyLong());
        verify(cacheAside, never()).markEmpty(anyString());
    }

    @Test
    void isContentLikedMissEmptyUserLikesWritesEmptyMarker() throws SQLException {
        stubContentSetScan(7L, 1L, false, false, null);
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenReturn(Collections.emptySet());

        assertFalse(service.isContentLiked(7L, 1L));
        verify(cacheAside).markEmpty(CacheKeys.userLikeSet(7L));
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void isContentLikedRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenReturn(Set.of(1L));

        assertTrue(service.isContentLiked(7L, 1L));
        // 三期 T2：降级经单飞全量装载作答（T4 反转后 = 用户点赞的全量内容），且不写回
        verify(contentLikeDao).findLikedContentIdsByUser(conn, 7L);
        verify(contentLikeDao, never()).isLiked(any(), anyLong(), anyLong());
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void isContentLikedRedisErrorConcurrentDegradeLoadsDbOnce() throws Exception {
        // 三期 T2 验收：Redis 停机时同一 key 的并发读只触发一次 DB 装载（T4 反转后同 key = 同一 user:likeSet）
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        int threads = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenAnswer(inv -> {
            loads.incrementAndGet();
            entered.countDown();
            release.await();
            return Set.of(1L);
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

    // ==================== 读路径：内容/评论点赞计数（CacheAside loader，T4 不动） ====================

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

    // ==================== 读路径：评论点赞成员（T4 反转：user:commentLikeSet 判 commentId） ====================

    @Test
    void isCommentLikedMissBackfillsUserSetAndContains() throws SQLException {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userCommentLikeSet(7L);
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> memberResp = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "9")).thenReturn(memberResp);
        when(commentLikeDao.findLikedCommentIdsByUser(conn, 7L)).thenReturn(Set.of(9L));

        assertTrue(service.isCommentLiked(7L, 9L));
        verify(jedis).sadd(eq(setKey), any(String[].class));
        verify(jedis).expire(eq(setKey), anyLong());
    }

    // ==================== 批量（T4 反转：单 set 多成员，一趟 pipeline 判 user:likeSet） ====================

    @Test
    void batchIsContentLikedEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = service.batchIsContentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
    }

    @Test
    void batchIsContentLikedHitDataAnswersMembersWithoutDb() {
        // 单 set 多成员：user:likeSet:{7} 命中即所有成员一趟 SISMEMBER，无 DB
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userLikeSet(7L);
        // Response 必须先建好再 stub Pipeline（避免 thenReturn 内嵌 stubbing，同文件惯例）
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> mem1 = booleanResponse(false);
        Response<Boolean> mem2 = booleanResponse(true);
        Response<Boolean> mem3 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "1")).thenReturn(mem1);
        when(p.sismember(setKey, "2")).thenReturn(mem2);
        when(p.sismember(setKey, "3")).thenReturn(mem3);

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L, 3L));

        assertEquals(false, result.get(1L));
        assertEquals(true, result.get(2L));
        assertEquals(false, result.get(3L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedEmptyMarkerAnswersAllFalse() {
        // 空标记（"该用户无点赞"）→ 全 false，无 DB
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userLikeSet(7L);
        Response<Boolean> emptyResp = booleanResponse(true);
        Response<Boolean> existsResp = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L));

        assertEquals(false, result.get(1L));
        assertEquals(false, result.get(2L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedMissAnswersDbAndBackfillsOnce() throws SQLException {
        // miss：dbAnswer 批量作答（DB 即真理，失败上抛）+ 单 set 只回填一次全量（best-effort）
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userLikeSet(7L);
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> mem1 = booleanResponse(false);
        Response<Boolean> mem2 = booleanResponse(false);
        Response<Boolean> mem3 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "1")).thenReturn(mem1);
        when(p.sismember(setKey, "2")).thenReturn(mem2);
        when(p.sismember(setKey, "3")).thenReturn(mem3);
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(1L, 2L, 3L))).thenReturn(Set.of(1L, 3L));
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenReturn(Set.of(1L, 3L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L, 3L));

        assertEquals(true, result.get(1L));
        assertEquals(false, result.get(2L));
        assertEquals(true, result.get(3L));
        // 单 set 只回填一次（T4 反转：替代原逐内容 key 各全量装载一次）
        verify(jedis).sadd(eq(setKey), any(String[].class));
        verify(jedis).expire(eq(setKey), anyLong());
    }

    @Test
    void batchIsContentLikedRedisErrorDegradesFromDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenReturn(Set.of(1L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L));

        assertEquals(true, result.get(1L));
        // 三期 T2：降级经单飞全量装载作答——不再走 targeted 批量查询，也不尝试回填写入
        verify(contentLikeDao).findLikedContentIdsByUser(conn, 7L);
        verify(contentLikeDao, never()).findLikedContentIds(any(), anyLong(), anyList());
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void batchIsCommentLikedEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
    }

    @Test
    void batchIsCommentLikedHitDataAnswersMembersWithoutDb() {
        // 单 set 多成员：user:commentLikeSet:{7} 命中即一趟 SISMEMBER，无 DB（与内容侧对称）
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userCommentLikeSet(7L);
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> mem9 = booleanResponse(true);
        Response<Boolean> mem10 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "9")).thenReturn(mem9);
        when(p.sismember(setKey, "10")).thenReturn(mem10);

        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, List.of(9L, 10L));

        assertEquals(true, result.get(9L));
        assertEquals(false, result.get(10L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsCommentLikedMissAnswersDbAndBackfillsOnce() throws SQLException {
        // miss：dbAnswer 批量作答 + 单 set 只回填一次全量（与内容侧对称，锁 userCommentLikeSet 接线）
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userCommentLikeSet(7L);
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> mem9 = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "9")).thenReturn(mem9);
        when(commentLikeDao.findLikedCommentIds(conn, 7L, List.of(9L))).thenReturn(Set.of(9L));
        when(commentLikeDao.findLikedCommentIdsByUser(conn, 7L)).thenReturn(Set.of(9L));

        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, List.of(9L));

        assertEquals(true, result.get(9L));
        verify(jedis).sadd(eq(setKey), any(String[].class));
        verify(jedis).expire(eq(setKey), anyLong());
    }

    // ==================== 批量回填 best-effort（第四期 T2 收口 SetCache 后的 L2 差异对照，T4 反转沿用） ====================

    /**
     * T1 登记差异（TASKS T1 执行回写）：收口前批量回填 loader DB 失败上抛（批量 500）；
     * 收口后 SetCache 契约 = best-effort——DB 答案照常返回，仅缓存受影响（4.2）。T4 反转后同契约。
     */
    @Test
    void batchIsContentLikedBackfillLoaderFailureIsBestEffort() throws SQLException {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String setKey = CacheKeys.userLikeSet(7L);
        // miss（三态桩）；Response 先建再 stub（避免内嵌 stubbing，同文件惯例）
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> memberResp = booleanResponse(false);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, "3")).thenReturn(memberResp);
        // dbAnswer 成立（DB 即真理）：用户点赞过 id=3
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(3L))).thenReturn(Set.of(3L));
        // 回填全量 loader 真失败（DB 故障）
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenThrow(new SQLException("db down"));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(3L));

        // 不抛；DB 答案照常返回（缓存失败不得导致业务失败，4.2）
        assertEquals(true, result.get(3L));
    }

    /**
     * 单成员 miss 装载 loader DB 失败仍上抛（与收口前一致，无行为漂移；T4 反转后 loader 换用户全量）——
     * SetCache.isMember 仅 catch CacheException，真实 DB 失败向上抛。
     */
    @Test
    void isContentLikedMissLoaderDbFailureStillThrows() throws SQLException {
        stubContentSetScan(7L, 1L, false, false, null);
        when(contentLikeDao.findLikedContentIdsByUser(conn, 7L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.isContentLiked(7L, 1L));
    }

    // ==================== 失效（内容/评论删除级联，4.5；T4 反转为仅计数失效） ====================

    @Test
    void deleteContentLikeInvalidatesCountOnly() {
        service.deleteContentLike(5L);

        verify(cacheAside).invalidate(CacheKeys.contentLikeCount(5L));
        // T4 反转：成员 key 为用户维度，内容删除不失效（残留成员指向已删除 id，不复用不外显）
        verify(cacheAside, never()).invalidate(CacheKeys.userLikeSet(5L));
    }

    @Test
    void deleteCommentLikeInvalidatesCountOnly() {
        service.deleteCommentLike(9L);

        verify(cacheAside).invalidate(CacheKeys.commentLikeCount(9L));
        verify(cacheAside, never()).invalidate(CacheKeys.userCommentLikeSet(9L));
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