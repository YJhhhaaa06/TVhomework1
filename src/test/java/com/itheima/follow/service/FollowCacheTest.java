package com.itheima.follow.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.cache.ZSetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.user.dao.UserDao;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * T5 关注关系缓存（双 ZSet + MULTI 双写 + 失败双 DEL + 三态读 + 单飞 + 降级）单测。
 *
 * <p>第四期 T3：读路径收口基建组件（同一 mock redis + 真实 SingleFlight + 同一 stats 组合注入）；
 * 第六期 T7（A1 缓存有序结构）：成员 key 由 Set 升级为 **ZSet**（score = 成员数值），
 * 读命令 SISMEMBER/SMEMBERS → ZSCORE/ZRANGE，写命令 SADD/SREM → ZADD/ZREM，
 * 并新增「按序窗口读」用例（ZRANGE[start,stop] + ZCARD）。
 *
 * <p><b>T11-C（前缀窗口装载，治 U-18）</b>：窗口 loader 改用 DAO 的**窗口 SQL**
 * （只查本页；`getAllFollowedUserIds` 全量 SQL 不参与分页读），集合完整性由
 * {@code partial:{key}} 标记表达；写路径遇部分态 → 双 DEL；判定（isFollowing）在部分态下
 * 未命中回落 DB 单行查询。本文件相应新增该组用例，并删除已无主代码调用方的
 * 粉丝方向全量读用例（原 {@code getFollowerIds} 随池 U-21 处置删除）。
 */
class FollowCacheTest {

    private static final long USER = 7L;
    private static final long FOLLOWED = 8L;

    private FollowDao followDao;
    private UserDao userDao;
    private TransactionTemplate tt;
    private Connection conn;
    private RedisAccess redis;
    private CacheAside cacheAside;
    private Jedis jedis;
    private FollowCache cache;
    private CacheStats stats;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        userDao = mock(UserDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        redis = mock(RedisAccess.class);
        cacheAside = mock(CacheAside.class);
        jedis = mock(Jedis.class);
        stats = new CacheStats();
        // 第四期 T3：读路径收口基建组件（复用同一 mock redis / 真实 SingleFlight / 同一 stats，
        // 保证既有三态/回填/降级并发与统计断言不因组件平移而破）；T7 起该组件是 ZSetCache
        ZSetCache zSetCache = new ZSetCache(redis, cacheAside, new SingleFlight(), stats);
        // 第四期 T6（R-01）：计数读写入缓存，构造补 UserDao
        cache = new FollowCache(followDao, userDao, tt, redis, zSetCache, cacheAside, stats);

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

    /** Response mock：必须在 when(...) 之前创建完毕，避免 thenReturn 内嵌 stubbing（UnfinishedStubbing）。 */
    private Response<Boolean> booleanResponse(Boolean v) {
        @SuppressWarnings("unchecked")
        Response<Boolean> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    /** ZSCORE 响应 mock（非 null = 成员；null = 非成员）。 */
    private Response<Double> doubleResponse(Double v) {
        @SuppressWarnings("unchecked")
        Response<Double> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    private Response<Long> longResponse(Long v) {
        @SuppressWarnings("unchecked")
        Response<Long> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    private Response<List<String>> listResponse(List<String> v) {
        @SuppressWarnings("unchecked")
        Response<List<String>> r = mock(Response.class);
        when(r.get()).thenReturn(v);
        return r;
    }

    /** 单条三态扫描（scanZSet）命中所给 [空标记, key 存在, ZSCORE]（partial 标记缺省不存在）。 */
    private void stubZSetScan(String zsetKey, Boolean empty, Boolean exists, Double score) {
        stubZSetScan(zsetKey, empty, exists, score, false);
    }

    /** 单条三态扫描（带部分装载标记，T11-C）。 */
    private void stubZSetScan(String zsetKey, Boolean empty, Boolean exists, Double score,
                              Boolean partial) {
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Double> scoreResp = doubleResponse(score);
        Response<Boolean> partialResp = booleanResponse(partial);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(emptyResp);
        when(p.exists(zsetKey)).thenReturn(existsResp);
        when(p.zscore(zsetKey, String.valueOf(FOLLOWED))).thenReturn(scoreResp);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(partialResp);
    }

    /** 列表类双探针（全量读 / 窗口读 / probePair 前两 x 两 y）命中所给 [empty, exists]（partial 缺省不存在）。 */
    private void stubExistsProbe(String zsetKey, Boolean empty, Boolean exists) {
        stubExistsProbe(zsetKey, empty, exists, false);
    }

    /** 列表类探针（带部分装载标记，T11-C）。 */
    private void stubExistsProbe(String zsetKey, Boolean empty, Boolean exists, Boolean partial) {
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Boolean> partialResp = booleanResponse(partial);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(emptyResp);
        when(p.exists(zsetKey)).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(partialResp);
    }

    private String followingKey(long userId) {
        return CacheKeys.userFollowing(userId);
    }

    private String followerKey(long userId) {
        return CacheKeys.userFollower(userId);
    }

    // ==================== 读-单条 isFollowing（三态 + 单飞回填 + 降级） ====================

    @Test
    void isFollowingEmptyMarkerReturnsFalseWithoutDb() {
        stubZSetScan(followingKey(USER), true, null, null);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingTrueWhenMember() {
        stubZSetScan(followingKey(USER), false, true, 1.0);

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingFalseWhenZSetHasNoMember() {
        stubZSetScan(followingKey(USER), false, true, null);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingPartialMissFallsBackToDbSingleRow() throws SQLException {
        // T11-C：部分装载态下 ZSCORE 未命中 ≠ 未关注 → 必须回落 DB 单行查询（前缀里查不到不等于不是成员）
        stubZSetScan(followingKey(USER), false, true, null, true);
        when(followDao.isFollowing(conn, USER, FOLLOWED)).thenReturn(true);

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        verify(followDao).isFollowing(conn, USER, FOLLOWED);
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong()); // 不得改用全量 loader
    }

    @Test
    void isFollowingPartialHitTrustsZscoreWithoutDb() {
        // 部分态但 ZSCORE 命中 → 直接 true，不再打 DB（省一次查询）
        stubZSetScan(followingKey(USER), false, true, 1.0, true);

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    // ==================== T9 滑动续期：命中顺带续期、空标记不续 ====================

    @Test
    void isFollowingHitDataRenewsKeyTtlButNotEmptyMarker() {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Double> scoreResp = doubleResponse(1.0);
        Response<Boolean> partialResp = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        String zsetKey = followingKey(USER);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(emptyResp);
        when(p.exists(zsetKey)).thenReturn(existsResp);
        when(p.zscore(zsetKey, String.valueOf(FOLLOWED))).thenReturn(scoreResp);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(partialResp);

        assertTrue(cache.isFollowing(USER, FOLLOWED));

        // hit-data：数据 key 续期（值=域 TTL，与写路径 expire 口径一致；读配置 getter 防分域调参断挂）
        verify(p).expire(zsetKey, AppConfig.getFollowTtlSeconds());
        // 空标记 key 从不被续期
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void isFollowingMissBackfillsZSetAndReturnsContains() throws SQLException {
        stubZSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(FOLLOWED, 9L));

        boolean result = cache.isFollowing(USER, FOLLOWED);

        assertTrue(result);
        verify(jedis).zadd(eq(followingKey(USER)), anyMap());
        verify(jedis).expire(eq(followingKey(USER)), anyLong());
        verify(cacheAside, never()).markEmpty(anyString());
    }

    @Test
    void isFollowingMissBackfillRecordsMissAndLoadToFollowDomain() throws SQLException {
        stubZSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(FOLLOWED, 9L));

        assertTrue(cache.isFollowing(USER, FOLLOWED));

        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void isFollowingMissEmptyFollowsWritesEmptyMarker() throws SQLException {
        stubZSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        // 三期 T4/N3：空集写空标记统一走 CacheAside.markEmpty（内含存在守卫，U-09 定向复用）
        verify(cacheAside).markEmpty(followingKey(USER));
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void writeEmptyMarkerSkipsWhenKeyAlreadyExists() throws SQLException {
        // 并发守卫回归（review 必修②）：回填读到旧空 DB，但 key 已被并发 cacheFollow 写入 → 不得覆盖。
        // 三期 T4/N3 起守卫内聚于 CacheAside.markEmpty（行为断言在 CacheAsideTest.markEmptySkipsWhenDataKeyExists），
        // 此处验证空集回填委托 markEmpty（守卫随公共实现生效）
        stubZSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());
        when(jedis.exists(followingKey(USER))).thenReturn(true);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(cacheAside).markEmpty(followingKey(USER));
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void isFollowingRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(FOLLOWED));

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        // 三期 T2：降级经单飞全量装载作答（替代原单行查询），且不写回
        verify(followDao).getAllFollowedUserIds(conn, USER);
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void isFollowingRedisErrorConcurrentDegradeLoadsDbOnce() throws Exception {
        // 三期 T2 验收：Redis 停机时同一 key 的并发读只触发一次 DB 装载
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        int threads = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenAnswer(inv -> {
            loads.incrementAndGet();
            entered.countDown();
            release.await();
            return List.of(FOLLOWED);
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> cache.isFollowing(USER, FOLLOWED)));
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

    // ==================== 读-批量 batchIsFollowing（同一 key 一趟 pipeline） ====================

    @Test
    void batchIsFollowingEmptyInputSkipsRedis() {
        Map<Long, Boolean> result = cache.batchIsFollowing(USER, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(redis, never()).executeVoid(any(Consumer.class));
        verify(redis, never()).execute(any(Function.class));
    }

    @Test
    void batchIsFollowingEmptyMarkerAllFalseWithoutDb() {
        Response<Boolean> emptyResp = booleanResponse(true);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> partialResp = booleanResponse(false);
        Response<Double> score8 = doubleResponse(null);
        Response<Double> score9 = doubleResponse(null);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(followingKey(USER)))).thenReturn(partialResp);
        when(p.zscore(followingKey(USER), "8")).thenReturn(score8);
        when(p.zscore(followingKey(USER), "9")).thenReturn(score9);

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(false, result.get(8L));
        assertEquals(false, result.get(9L));
        verify(tt, never()).execute(any());
        // R-06 ②（第四期 T7）：批量 pipeline 续期直接断言——hit-empty 亦入列续期（key 不存在返回 0 无效果）、空标记不续
        verify(p).expire(followingKey(USER), AppConfig.getFollowTtlSeconds());
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void batchIsFollowingMixedMembersOnExistingZSet() {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> partialResp = booleanResponse(false);
        Response<Double> score8 = doubleResponse(8.0);
        Response<Double> score9 = doubleResponse(null);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(followingKey(USER)))).thenReturn(partialResp);
        when(p.zscore(followingKey(USER), "8")).thenReturn(score8);
        when(p.zscore(followingKey(USER), "9")).thenReturn(score9);

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(true, result.get(8L));
        assertEquals(false, result.get(9L));
        verify(tt, never()).execute(any());
        // R-06 ②（第四期 T7）：批量 hit-data 续期直接断言——对数据 key 入列域 TTL、空标记不续
        verify(p).expire(followingKey(USER), AppConfig.getFollowTtlSeconds());
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void batchIsFollowingPartialMissFallsBackToDbWithoutFullBackfill() throws SQLException {
        // T11-C：部分态下未命中成员回落既有 dbAnswer（批量 IN），且**不回填全量**（U-18 不放大装载量）
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> partialResp = booleanResponse(true);
        Response<Double> score8 = doubleResponse(8.0);
        Response<Double> score9 = doubleResponse(null);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(followingKey(USER)))).thenReturn(partialResp);
        when(p.zscore(followingKey(USER), "8")).thenReturn(score8);
        when(p.zscore(followingKey(USER), "9")).thenReturn(score9);
        when(followDao.getFollowedIds(conn, USER, List.of(9L))).thenReturn(Set.of(9L));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(true, result.get(8L));
        assertEquals(true, result.get(9L));
        verify(followDao).getFollowedIds(conn, USER, List.of(9L));
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong());
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void batchIsFollowingMissBackfillsAllFromDb() throws SQLException {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> partialResp = booleanResponse(false);
        Response<Double> score8 = doubleResponse(null);
        Response<Double> score9 = doubleResponse(null);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(followingKey(USER)))).thenReturn(partialResp);
        when(p.zscore(followingKey(USER), "8")).thenReturn(score8);
        when(p.zscore(followingKey(USER), "9")).thenReturn(score9);
        when(followDao.getFollowedIds(conn, USER, List.of(8L, 9L))).thenReturn(Set.of(9L));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(9L));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(false, result.get(8L));
        assertEquals(true, result.get(9L));
        verify(jedis).zadd(eq(followingKey(USER)), anyMap());
        // R-06 ②（第四期 T7）：探针续期入列（miss 时 key 不存在返回 0 无效果；回填 TTL 由 writeZSet 负责）
        verify(p).expire(followingKey(USER), AppConfig.getFollowTtlSeconds());
        verify(p, never()).expire(startsWith("empty:"), anyLong());
    }

    @Test
    void batchIsFollowingRedisErrorDegradesFromDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(8L));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L));

        assertEquals(true, result.get(8L));
        // 三期 T2：降级经单飞全量装载作答——不再走 targeted 批量查询，也不尝试回填写入
        verify(followDao).getAllFollowedUserIds(conn, USER);
        verify(followDao, never()).getFollowedIds(any(), anyLong(), anyList());
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void batchIsFollowingBackfillFailureKeepsDbResult() throws SQLException {
        // 建议项 ②回归：DB 兜底已算出结果，回填全量查询抛异常 → 结果照常返回、不 500
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> partialResp = booleanResponse(false);
        Response<Double> score8 = doubleResponse(null);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(followingKey(USER)))).thenReturn(partialResp);
        when(p.zscore(followingKey(USER), "8")).thenReturn(score8);
        when(followDao.getFollowedIds(conn, USER, List.of(8L))).thenReturn(Set.of(8L));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenThrow(new ServerException("服务器异常，查询关注列表失败"));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L));

        assertEquals(true, result.get(8L));
        verify(redis).executeVoid(any(Consumer.class));
    }

    // ==================== 读-关注列表全量（ZRANGE / 空标记 / 部分态补齐 / 降级） ====================

    @Test
    void getFollowingIdsEmptyMarkerReturnsEmptyWithoutDb() {
        stubExistsProbe(followingKey(USER), true, null);

        List<Long> result = cache.getFollowingIds(USER);

        assertTrue(result.isEmpty());
        verify(tt, never()).execute(any());
    }

    @Test
    void getFollowingIdsZrangeIsAscending() {
        stubExistsProbe(followingKey(USER), false, true);
        // ZSet 天然按 score（= 成员数值）升序返回，与改造前 sortIds 升序口径一致
        when(jedis.zrange(followingKey(USER), 0, -1)).thenReturn(List.of("3", "8", "9"));

        List<Long> result = cache.getFollowingIds(USER);

        assertEquals(List.of(3L, 8L, 9L), result);
        verify(tt, never()).execute(any());
    }

    @Test
    void getFollowingIdsMissBackfillsAndReturnsDbList() throws SQLException {
        stubExistsProbe(followingKey(USER), false, false);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(8L, 9L));

        List<Long> result = cache.getFollowingIds(USER);

        assertEquals(List.of(8L, 9L), result);
        verify(jedis).zadd(eq(followingKey(USER)), anyMap());
        verify(jedis).expire(eq(followingKey(USER)), anyLong());
    }

    @Test
    void getFollowingIdsMissEmptyWritesEmptyMarker() throws SQLException {
        stubExistsProbe(followingKey(USER), false, false);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());

        List<Long> result = cache.getFollowingIds(USER);

        assertTrue(result.isEmpty());
        // 三期 T4/N3：空集写空标记统一走 CacheAside.markEmpty（内含存在守卫，U-09 定向复用）
        verify(cacheAside).markEmpty(followingKey(USER));
    }

    @Test
    void getFollowingIdsPartialCompletesWithFullLoaderBeforeReturning() throws SQLException {
        // T11-C 最危险点（R3）：全量读遇部分态必须补齐——feed 依赖全量关注 ids，返回前缀会静默漏人
        stubExistsProbe(followingKey(USER), false, true, true);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(3L, 8L, 9L));
        when(jedis.zrange(followingKey(USER), 0, -1)).thenReturn(List.of("3", "8", "9"));

        List<Long> result = cache.getFollowingIds(USER);

        assertEquals(List.of(3L, 8L, 9L), result);
        verify(followDao).getAllFollowedUserIds(conn, USER); // 补齐走全量 loader
        verify(jedis).zadd(eq(followingKey(USER)), anyMap()); // ZADD 合并（不 DEL）
        verify(jedis).del(CacheKeys.partial(followingKey(USER))); // 补齐后清标记 → 转完整态
    }

    @Test
    void getFollowingIdsRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(8L));

        List<Long> result = cache.getFollowingIds(USER);

        assertEquals(List.of(8L), result);
        verify(followDao).getAllFollowedUserIds(conn, USER);
    }

    // ==================== 读-按序窗口（T7 A1：ZRANGE[start,stop] + ZCARD；T11-C 前缀装载） ====================

    /** 窗口命中桩：探针 [empty, exists, partial=false] + 窗口 pipeline 的 zrange/zcard。 */
    private void stubWindow(String zsetKey, Boolean empty, Boolean exists,
                            long offset, int count, List<String> page, Long card) {
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Boolean> partialResp = booleanResponse(false);
        Response<List<String>> rangeResp = listResponse(page);
        Response<Long> cardResp = longResponse(card);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(emptyResp);
        when(p.exists(zsetKey)).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(partialResp);
        when(p.zrange(zsetKey, offset, offset + count - 1L)).thenReturn(rangeResp);
        when(p.zcard(zsetKey)).thenReturn(cardResp);
    }

    /**
     * 部分态窗口桩：探针 [empty=false, exists=true, partial=true] +
     * **非 pipeline** 的 ZCARD / ZRANGE（readPartialWindow / readPage 直接走 Jedis 命令）。
     */
    private void stubPartialWindow(String zsetKey, long known, long offset, int count,
                                   List<String> page) {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> partialResp = booleanResponse(true);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(zsetKey))).thenReturn(emptyResp);
        when(p.exists(zsetKey)).thenReturn(existsResp);
        when(p.exists(CacheKeys.partial(zsetKey))).thenReturn(partialResp);
        when(jedis.zcard(zsetKey)).thenReturn(known);
        when(jedis.zrange(zsetKey, offset, offset + count - 1L)).thenReturn(page);
    }

    @Test
    void getFollowingWindowHitDataTakesPageAndTotalWithoutDb() throws SQLException {
        stubWindow(followingKey(USER), false, true, 2L, 2, List.of("8", "9"), 7L);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 2L, 2);

        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(7L, window.getTotal(), "完整态 total 仍走 ZCARD（与 T7 逐字节一致）");
        verify(tt, never()).execute(any());
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong());
    }

    @Test
    void getFollowingWindowHitEmptyReturnsEmptyWindow() {
        stubWindow(followingKey(USER), true, null, 0L, 10, null, null);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 0L, 10);

        assertTrue(window.getIds().isEmpty());
        assertEquals(0L, window.getTotal());
        verify(tt, never()).execute(any());
    }

    @Test
    void getFollowingWindowMissLoadsOnlyWindowNotFullList() throws SQLException {
        // T11-C：冷 key 只装载 [0, offset+count)，不再触发 getAllFollowedUserIds 全量装载
        stubWindow(followingKey(USER), false, false, 0L, 2, null, null);
        when(jedis.zcard(followingKey(USER))).thenReturn(2L);
        when(jedis.zrange(followingKey(USER), 0L, 1L)).thenReturn(List.of("8", "9"));
        when(followDao.getFollowedUserIdsInWindow(conn, USER, 0L, 2)).thenReturn(List.of(8L, 9L));
        when(cacheAside.get(eq(CacheKeys.userFollowCount(USER)), eq(Integer.class), any(), anyLong()))
                .thenReturn(2);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 0L, 2);

        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(2L, window.getTotal());
        verify(followDao).getFollowedUserIdsInWindow(conn, USER, 0L, 2);
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong()); // U-18：分页读不再全量装载
        verify(jedis).setex(CacheKeys.partial(followingKey(USER)), AppConfig.getFollowTtlSeconds(),
                CacheKeys.PARTIAL_MARKER_VALUE); // 取满 = 可能还有 → 打部分装载标记
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.MISS));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void getFollowingWindowPartialWithinPrefixDoesNotLoad() throws SQLException {
        stubPartialWindow(followingKey(USER), 200L, 0L, 2, List.of("8", "9"));
        // 部分态 total 走本域计数口径（命中计数 key，不落 DB）
        when(cacheAside.get(eq(CacheKeys.userFollowCount(USER)), eq(Integer.class), any(), anyLong()))
                .thenReturn(200);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 0L, 2);

        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(200L, window.getTotal());
        verify(tt, never()).execute(any()); // 页落在已知前缀内：零 DB；total 命中计数 key
        verify(followDao, never()).getFollowedUserIdsInWindow(any(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void getFollowingWindowPartialBeyondPrefixLoadsOnlyGap() throws SQLException {
        // 已装载 W=2，请求 [4,6) → 只补 [2,6)（DB 取满 4 行 = 未到底 → 仍是部分态）
        stubPartialWindow(followingKey(USER), 2L, 4L, 2, List.of("30", "40"));
        when(followDao.getFollowedUserIdsInWindow(conn, USER, 2L, 4))
                .thenReturn(List.of(20L, 25L, 30L, 40L));
        when(cacheAside.get(eq(CacheKeys.userFollowCount(USER)), eq(Integer.class), any(), anyLong()))
                .thenReturn(500);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 4L, 2);

        assertEquals(List.of(30L, 40L), window.getIds());
        assertEquals(500L, window.getTotal(), "部分态 total 走本域计数口径");
        verify(followDao).getFollowedUserIdsInWindow(conn, USER, 2L, 4);
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong());
    }

    @Test
    void getFollowingWindowRedisErrorQueriesDbWindowWithoutLoad() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(followDao.getFollowedUserIdsInWindow(conn, USER, 1L, 2)).thenReturn(List.of(8L, 9L));
        when(cacheAside.get(eq(CacheKeys.userFollowCount(USER)), eq(Integer.class), any(), anyLong()))
                .thenReturn(30);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 1L, 2);

        // T11-C 降级语义：DB 窗口直查（只查被看的那一段）、不装载不写回
        assertEquals(List.of(8L, 9L), window.getIds());
        assertEquals(30L, window.getTotal());
        verify(followDao).getFollowedUserIdsInWindow(conn, USER, 1L, 2);
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong());
        verify(jedis, never()).zadd(anyString(), anyMap());
    }

    @Test
    void getFollowingWindowOffsetBeyondTotalReturnsEmptyButKeepsTotal() {
        stubWindow(followingKey(USER), false, true, 10L, 2, Collections.emptyList(), 3L);

        ZSetCache.Window window = cache.getFollowingWindow(USER, 10L, 2);

        assertTrue(window.getIds().isEmpty());
        assertEquals(3L, window.getTotal());
    }

    @Test
    void getFollowerWindowUsesFollowerKeyAndWindowLoader() throws SQLException {
        stubWindow(followerKey(USER), false, false, 0L, 1, null, null);
        when(jedis.zcard(followerKey(USER))).thenReturn(1L);
        when(jedis.zrange(followerKey(USER), 0L, 0L)).thenReturn(List.of("8"));
        when(followDao.getFollowerUserIdsInWindow(conn, USER, 0L, 1)).thenReturn(List.of(8L));
        when(cacheAside.get(eq(CacheKeys.userFollowerCount(USER)), eq(Integer.class), any(), anyLong()))
                .thenReturn(1);

        ZSetCache.Window window = cache.getFollowerWindow(USER, 0L, 1);

        assertEquals(List.of(8L), window.getIds());
        assertEquals(1L, window.getTotal());
        verify(followDao).getFollowerUserIdsInWindow(conn, USER, 0L, 1);
        verify(followDao, never()).getAllFollowedUserIds(any(), anyLong());
        verify(followDao, never()).getFollowerUserIds(any(), anyLong()); // U-18：不再全量拉粉丝
    }

    // ==================== 读-关注/粉丝计数（T6 R-01：CacheAside 独立计数 key） ====================

    @Test
    void getFollowCountHitDataReturnsCachedWithoutDb() {
        String key = CacheKeys.userFollowCount(USER);
        when(cacheAside.get(eq(key), eq(Integer.class), any(), anyLong())).thenReturn(42);

        assertEquals(42, cache.getFollowCount(USER));
        verify(tt, never()).execute(any());
    }

    @Test
    void getFollowCountMissLoadsFromDbAndReturnsValue() throws SQLException {
        // cacheAside mock 返 null（命中空标记防御分支）→ LOAD 兜底重载 DB 单列计数
        when(userDao.getFollowCountById(conn, USER)).thenReturn(10);

        assertEquals(10, cache.getFollowCount(USER));
        verify(userDao).getFollowCountById(conn, USER);
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.LOAD));
    }

    @Test
    void getFollowerCountZeroIsValidData() throws SQLException {
        // 0 是合法计数（DB loader 恒非 null）；miss 装载返回 0 照常返回
        when(userDao.getFollowerCountById(conn, USER)).thenReturn(0);

        assertEquals(0, cache.getFollowerCount(USER));
        verify(userDao).getFollowerCountById(conn, USER);
    }

    @Test
    void getFollowCountLoaderSqlErrorThrowsServerException() throws SQLException {
        when(userDao.getFollowCountById(conn, USER)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> cache.getFollowCount(USER));
    }

    @Test
    void getFollowerCountCacheAsideHitDataSkipsDb() throws SQLException {
        String key = CacheKeys.userFollowerCount(USER);
        when(cacheAside.get(eq(key), eq(Integer.class), any(), anyLong())).thenReturn(7);

        assertEquals(7, cache.getFollowerCount(USER));
        verify(userDao, never()).getFollowerCountById(any(), anyLong());
    }

    // ==================== 写路径 cacheFollow（条件双写 + MULTI + 失败双 DEL） ====================

    /** probePair 六探针桩（T11-C：两条 data key 的 exists/empty/partial）。 */
    private void stubPairProbe(String followingKey, String followerKey,
                               boolean existsFg, boolean emptyFg, boolean partialFg,
                               boolean existsFr, boolean emptyFr, boolean partialFr) {
        Response<Boolean> existsFgResp = booleanResponse(existsFg);
        Response<Boolean> emptyFgResp = booleanResponse(emptyFg);
        Response<Boolean> partialFgResp = booleanResponse(partialFg);
        Response<Boolean> existsFrResp = booleanResponse(existsFr);
        Response<Boolean> emptyFrResp = booleanResponse(emptyFr);
        Response<Boolean> partialFrResp = booleanResponse(partialFr);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey)).thenReturn(existsFgResp);
        when(p.exists(CacheKeys.empty(followingKey))).thenReturn(emptyFgResp);
        when(p.exists(CacheKeys.partial(followingKey))).thenReturn(partialFgResp);
        when(p.exists(followerKey)).thenReturn(existsFrResp);
        when(p.exists(CacheKeys.empty(followerKey))).thenReturn(emptyFrResp);
        when(p.exists(CacheKeys.partial(followerKey))).thenReturn(partialFrResp);
    }

    @Test
    void cacheFollowBothKeysReadyZaddViaMulti() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                true, false, false, true, false, false);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheFollow(USER, FOLLOWED);

        // score = 成员自身 id（与 ZSet 有序读口径一致：按 id 升序）
        verify(multi).zadd(followingKey(USER), (double) FOLLOWED, String.valueOf(FOLLOWED));
        verify(multi).zadd(followerKey(FOLLOWED), (double) USER, String.valueOf(USER));
        verify(multi).expire(eq(followingKey(USER)), anyLong());
        verify(multi).expire(eq(followerKey(FOLLOWED)), anyLong());
        verify(multi).exec();
        verify(multi, never()).del(anyString());
    }

    @Test
    void cacheFollowClearsEmptyMarkersWhenEmptyHit() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                false, true, false, true, false, false);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheFollow(USER, FOLLOWED);

        verify(multi).del(CacheKeys.empty(followingKey(USER)));
        verify(multi).zadd(followingKey(USER), (double) FOLLOWED, String.valueOf(FOLLOWED));
        verify(multi).zadd(followerKey(FOLLOWED), (double) USER, String.valueOf(USER));
        verify(multi).exec();
    }

    @Test
    void cacheFollowColdKeyInvalidatesPairInsteadOfCreatingPartial() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                false, false, false, false, false, false);

        cache.cacheFollow(USER, FOLLOWED);

        // 三件套双 DEL（数据 key + 空标记 + 部分装载标记），不创建残缺集
        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheFollowPartialKeyInvalidatesPair() {
        // T11-C：部分装载态 → 关注会插入"非前缀成员"，破坏 ZRANGE offset 语义 → 双 DEL，不增量写
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                true, false, true, true, false, false);

        cache.cacheFollow(USER, FOLLOWED);

        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheFollowRedisErrorInvalidatesTriplePair() {
        // 首次 executeVoid（探针 + MULTI）抛错 → 兜底失效（第二次 executeVoid）按**三件套**口径双 DEL
        doThrow(new CacheException("redis down")).doAnswer(inv -> {
            Consumer<Jedis> c = inv.getArgument(0);
            c.accept(jedis);
            return null;
        }).when(redis).executeVoid(any(Consumer.class));

        cache.cacheFollow(USER, FOLLOWED);

        // T11-C：残留 partial 标记会让下次装载被误读为"仍是前缀"，必须与数据 key / 空标记一起清
        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
    }

    @Test
    void cacheFollowRedisTotallyDownStillDoesNotThrow() {
        // 连兜底双 DEL 也不可用：不抛出（缓存失败不得导致业务失败）
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        assertDoesNotThrow(() -> cache.cacheFollow(USER, FOLLOWED));
    }

    // ==================== 写路径 cacheUnfollow（条件 ZREM + 失败双 DEL） ====================

    @Test
    void cacheUnfollowBothKeysReadyZremViaMulti() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                true, false, false, true, false, false);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(multi).zrem(followingKey(USER), String.valueOf(FOLLOWED));
        verify(multi).zrem(followerKey(FOLLOWED), String.valueOf(USER));
        verify(multi).expire(eq(followingKey(USER)), anyLong());
        verify(multi).expire(eq(followerKey(FOLLOWED)), anyLong());
        verify(multi).exec();
    }

    @Test
    void cacheUnfollowEmptyOrColdKeyInvalidatesPair() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                false, true, false, true, false, false);

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheUnfollowPartialKeyInvalidatesPair() {
        // T11-C：部分态下取关会在前缀里留"洞" → 双 DEL
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                true, false, false, true, false, true);

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheUnfollowRedisErrorInvalidatesTriplePair() {
        doThrow(new CacheException("redis down")).doAnswer(inv -> {
            Consumer<Jedis> c = inv.getArgument(0);
            c.accept(jedis);
            return null;
        }).when(redis).executeVoid(any(Consumer.class));

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                CacheKeys.partial(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)),
                CacheKeys.partial(followerKey(FOLLOWED)));
    }

    @Test
    void cacheUnfollowRedisTotallyDownStillDoesNotThrow() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        assertDoesNotThrow(() -> cache.cacheUnfollow(USER, FOLLOWED));
    }

    // ==================== 写路径 计数条件增量（T6 R-01：exists 才 INCRBY，独立失败隔离） ====================

    /** 成员双 key 均就绪的 pipeline/multi 桩（写路径共用于计数调整用例）。 */
    private void stubMemberPairReady() {
        stubPairProbe(followingKey(USER), followerKey(FOLLOWED),
                true, false, false, true, false, false);
        when(jedis.multi()).thenReturn(mock(Transaction.class));
    }

    @Test
    void cacheFollowAdjustsCountsByPlusOne() {
        stubMemberPairReady();

        cache.cacheFollow(USER, FOLLOWED);

        verify(jedis).eval(FollowCache.FOLLOW_COUNT_ADJUST_SCRIPT,
                List.of(CacheKeys.userFollowCount(USER), CacheKeys.userFollowerCount(FOLLOWED)),
                List.of("1"));
    }

    @Test
    void cacheUnfollowAdjustsCountsByMinusOne() {
        stubMemberPairReady();

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(jedis).eval(FollowCache.FOLLOW_COUNT_ADJUST_SCRIPT,
                List.of(CacheKeys.userFollowCount(USER), CacheKeys.userFollowerCount(FOLLOWED)),
                List.of("-1"));
    }

    @Test
    void cacheFollowCountAdjustFailureInvalidatesCountKeysWithoutThrowing() {
        stubMemberPairReady();
        // 仅计数 EVAL 失败（成员 MULTI 块正常执行）→ 失效两计数 key 读自愈，不抛出
        doThrow(new CacheException("redis down")).when(jedis).eval(anyString(), anyList(), anyList());

        assertDoesNotThrow(() -> cache.cacheFollow(USER, FOLLOWED));

        verify(cacheAside).invalidate(CacheKeys.userFollowCount(USER),
                CacheKeys.userFollowerCount(FOLLOWED));
    }
}
