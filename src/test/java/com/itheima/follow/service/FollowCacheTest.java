package com.itheima.follow.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T5 关注关系缓存（双 Set + MULTI 双写 + 失败双 DEL + 三态读 + 单飞 + 降级）单测。
 * 风格对齐 LikeCacheServiceTest：mock DAO/RedisAccess/CacheAside + 真实 SingleFlight + tt 跑 loader；
 * 通过 Mockito 令 RedisAccess.execute/Void 作用于 mock Jedis（含 pipeline/multi），可验证 key 与写命令。
 */
class FollowCacheTest {

    private static final long USER = 7L;
    private static final long FOLLOWED = 8L;

    private FollowDao followDao;
    private TransactionTemplate tt;
    private Connection conn;
    private RedisAccess redis;
    private CacheAside cacheAside;
    private Jedis jedis;
    private FollowCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        followDao = mock(FollowDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        redis = mock(RedisAccess.class);
        cacheAside = mock(CacheAside.class);
        jedis = mock(Jedis.class);
        cache = new FollowCache(followDao, tt, redis, new SingleFlight(), cacheAside);

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

    /** 单条三态扫描（scanSet）命中所给 [空标记, set 存在, 成员]。 */
    private void stubSetScan(String setKey, Boolean empty, Boolean exists, Boolean member) {
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Response<Boolean> memberResp = booleanResponse(member);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
        when(p.sismember(setKey, String.valueOf(FOLLOWED))).thenReturn(memberResp);
    }

    /** 列表类双探针（getSetMembers / probePair 前两 x 两 y）命中所给 [empty, exists]。 */
    private void stubExistsProbe(String setKey, Boolean empty, Boolean exists) {
        Response<Boolean> emptyResp = booleanResponse(empty);
        Response<Boolean> existsResp = booleanResponse(exists);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(setKey))).thenReturn(emptyResp);
        when(p.exists(setKey)).thenReturn(existsResp);
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
        stubSetScan(followingKey(USER), true, null, null);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingTrueWhenMember() {
        stubSetScan(followingKey(USER), false, true, true);

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingFalseWhenSetHasNoMember() {
        stubSetScan(followingKey(USER), false, true, false);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(tt, never()).execute(any());
    }

    @Test
    void isFollowingMissBackfillsSetAndReturnsContains() throws SQLException {
        stubSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(FOLLOWED, 9L));

        boolean result = cache.isFollowing(USER, FOLLOWED);

        assertTrue(result);
        verify(jedis).sadd(eq(followingKey(USER)), any(String[].class));
        verify(jedis).expire(eq(followingKey(USER)), anyLong());
        verify(cacheAside, never()).markEmpty(anyString());
    }

    @Test
    void isFollowingMissEmptyFollowsWritesEmptyMarker() throws SQLException {
        stubSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());
        when(jedis.exists(followingKey(USER))).thenReturn(false);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(jedis).setex(eq(CacheKeys.empty(followingKey(USER))),
                eq(CacheKeys.EMPTY_MARKER_TTL_SECONDS), eq(CacheKeys.EMPTY_MARKER_VALUE));
        verify(jedis, never()).sadd(anyString(), any(String[].class));
    }

    @Test
    void writeEmptyMarkerSkipsWhenSetAlreadyExists() throws SQLException {
        // 并发守卫回归（review 必修②）：回填读到旧空 DB，但 set 已被并发 cacheFollow 写入 → 不得覆盖
        stubSetScan(followingKey(USER), false, false, null);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());
        when(jedis.exists(followingKey(USER))).thenReturn(true);

        assertFalse(cache.isFollowing(USER, FOLLOWED));
        verify(jedis, never()).setex(anyString(), anyLong(), anyString());
        verify(jedis, never()).del(anyString());
    }

    @Test
    void isFollowingRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(followDao.isFollowing(conn, USER, FOLLOWED)).thenReturn(true);

        assertTrue(cache.isFollowing(USER, FOLLOWED));
        verify(followDao).isFollowing(conn, USER, FOLLOWED);
    }

    // ==================== 读-批量 batchIsFollowing（同一 set 一趟 pipeline） ====================

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
        Response<Boolean> mem8 = booleanResponse(false);
        Response<Boolean> mem9 = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.sismember(followingKey(USER), "8")).thenReturn(mem8);
        when(p.sismember(followingKey(USER), "9")).thenReturn(mem9);

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(false, result.get(8L));
        assertEquals(false, result.get(9L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsFollowingMixedMembersOnExistingSet() {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(true);
        Response<Boolean> mem8 = booleanResponse(true);
        Response<Boolean> mem9 = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.sismember(followingKey(USER), "8")).thenReturn(mem8);
        when(p.sismember(followingKey(USER), "9")).thenReturn(mem9);

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(true, result.get(8L));
        assertEquals(false, result.get(9L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsFollowingMissBackfillsAllFromDb() throws SQLException {
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> mem8 = booleanResponse(false);
        Response<Boolean> mem9 = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.sismember(followingKey(USER), "8")).thenReturn(mem8);
        when(p.sismember(followingKey(USER), "9")).thenReturn(mem9);
        when(followDao.getFollowedIds(conn, USER, List.of(8L, 9L))).thenReturn(Set.of(9L));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(9L));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L, 9L));

        assertEquals(false, result.get(8L));
        assertEquals(true, result.get(9L));
        verify(jedis).sadd(eq(followingKey(USER)), any(String[].class));
    }

    @Test
    void batchIsFollowingRedisErrorAllBackfilledFromDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));
        when(followDao.getFollowedIds(conn, USER, List.of(8L))).thenReturn(Set.of(8L));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L));

        assertEquals(true, result.get(8L));
        verify(followDao).getFollowedIds(conn, USER, List.of(8L));
    }

    @Test
    void batchIsFollowingBackfillFailureKeepsDbResult() throws SQLException {
        // 建议项 ②回归：DB 兜底已算出结果，回填全量查询抛异常 → 结果照常返回、不 500
        Response<Boolean> emptyResp = booleanResponse(false);
        Response<Boolean> existsResp = booleanResponse(false);
        Response<Boolean> mem8 = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyResp);
        when(p.exists(followingKey(USER))).thenReturn(existsResp);
        when(p.sismember(followingKey(USER), "8")).thenReturn(mem8);
        when(followDao.getFollowedIds(conn, USER, List.of(8L))).thenReturn(Set.of(8L));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenThrow(new ServerException("服务器异常，查询关注列表失败"));

        Map<Long, Boolean> result = cache.batchIsFollowing(USER, List.of(8L));

        assertEquals(true, result.get(8L));
        verify(redis).executeVoid(any(Consumer.class));
    }

    // ==================== 读-关注/粉丝列表（smembers / 空标记 / 单飞回填 / 降级） ====================

    @Test
    void getFollowingIdsEmptyMarkerReturnsEmptyWithoutDb() {
        stubExistsProbe(followingKey(USER), true, null);

        List<Long> result = cache.getFollowingIds(USER);

        assertTrue(result.isEmpty());
        verify(tt, never()).execute(any());
    }

    @Test
    void getFollowingIdsSmembersSortedDeterministically() {
        stubExistsProbe(followingKey(USER), false, true);
        when(jedis.smembers(followingKey(USER))).thenReturn(Set.of("9", "3", "8"));

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
        verify(jedis).sadd(eq(followingKey(USER)), any(String[].class));
        verify(jedis).expire(eq(followingKey(USER)), anyLong());
    }

    @Test
    void getFollowingIdsMissEmptyWritesEmptyMarker() throws SQLException {
        stubExistsProbe(followingKey(USER), false, false);
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(Collections.emptyList());
        when(jedis.exists(followingKey(USER))).thenReturn(false);

        List<Long> result = cache.getFollowingIds(USER);

        assertTrue(result.isEmpty());
        verify(jedis).setex(eq(CacheKeys.empty(followingKey(USER))),
                eq(CacheKeys.EMPTY_MARKER_TTL_SECONDS), eq(CacheKeys.EMPTY_MARKER_VALUE));
    }

    @Test
    void getFollowingIdsRedisErrorDegradesToDb() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).execute(any(Function.class));
        when(followDao.getAllFollowedUserIds(conn, USER)).thenReturn(List.of(8L));

        List<Long> result = cache.getFollowingIds(USER);

        assertEquals(List.of(8L), result);
        verify(followDao).getAllFollowedUserIds(conn, USER);
    }

    @Test
    void getFollowerIdsReturnsFollowersFromSetOrDb() throws SQLException {
        stubExistsProbe(followerKey(USER), false, false);
        when(followDao.getFollowerUserIds(conn, USER)).thenReturn(List.of(8L));

        List<Long> result = cache.getFollowerIds(USER);

        assertEquals(List.of(8L), result);
        verify(followDao).getFollowerUserIds(conn, USER);
    }

    // ==================== 写路径 cacheFollow（条件双写 + MULTI + 失败双 DEL） ====================

    @Test
    void cacheFollowBothSetsReadyWritesViaMulti() {
        Response<Boolean> existsFg = booleanResponse(true);
        Response<Boolean> emptyFg = booleanResponse(false);
        Response<Boolean> existsFr = booleanResponse(true);
        Response<Boolean> emptyFr = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey(USER))).thenReturn(existsFg);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyFg);
        when(p.exists(followerKey(FOLLOWED))).thenReturn(existsFr);
        when(p.exists(CacheKeys.empty(followerKey(FOLLOWED)))).thenReturn(emptyFr);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheFollow(USER, FOLLOWED);

        verify(multi).sadd(followingKey(USER), String.valueOf(FOLLOWED));
        verify(multi).sadd(followerKey(FOLLOWED), String.valueOf(USER));
        verify(multi).expire(eq(followingKey(USER)), anyLong());
        verify(multi).expire(eq(followerKey(FOLLOWED)), anyLong());
        verify(multi).exec();
        verify(multi, never()).del(anyString());
    }

    @Test
    void cacheFollowClearsEmptyMarkersWhenEmptyHit() {
        Response<Boolean> existsFg = booleanResponse(false);
        Response<Boolean> emptyFg = booleanResponse(true);
        Response<Boolean> existsFr = booleanResponse(true);
        Response<Boolean> emptyFr = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey(USER))).thenReturn(existsFg);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyFg);
        when(p.exists(followerKey(FOLLOWED))).thenReturn(existsFr);
        when(p.exists(CacheKeys.empty(followerKey(FOLLOWED)))).thenReturn(emptyFr);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheFollow(USER, FOLLOWED);

        verify(multi).del(CacheKeys.empty(followingKey(USER)));
        verify(multi).sadd(followingKey(USER), String.valueOf(FOLLOWED));
        verify(multi).sadd(followerKey(FOLLOWED), String.valueOf(USER));
        verify(multi).exec();
    }

    @Test
    void cacheFollowColdKeyInvalidatesPairInsteadOfCreatingPartial() {
        Response<Boolean> existsFg = booleanResponse(false);
        Response<Boolean> emptyFg = booleanResponse(false);
        Response<Boolean> existsFr = booleanResponse(false);
        Response<Boolean> emptyFr = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey(USER))).thenReturn(existsFg);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyFg);
        when(p.exists(followerKey(FOLLOWED))).thenReturn(existsFr);
        when(p.exists(CacheKeys.empty(followerKey(FOLLOWED)))).thenReturn(emptyFr);

        cache.cacheFollow(USER, FOLLOWED);

        // 双 DEL（数据 key + 空标记 间隔解锁，见 invalidateKeysQuietly），不创建残缺集
        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheFollowRedisErrorInvalidatesPair() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        cache.cacheFollow(USER, FOLLOWED);

        verify(cacheAside).invalidate(followingKey(USER), followerKey(FOLLOWED));
    }

    // ==================== 写路径 cacheUnfollow（条件 SREM + 失败双 DEL） ====================

    @Test
    void cacheUnfollowBothSetsReadySremViaMulti() {
        Response<Boolean> existsFg = booleanResponse(true);
        Response<Boolean> emptyFg = booleanResponse(false);
        Response<Boolean> existsFr = booleanResponse(true);
        Response<Boolean> emptyFr = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey(USER))).thenReturn(existsFg);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyFg);
        when(p.exists(followerKey(FOLLOWED))).thenReturn(existsFr);
        when(p.exists(CacheKeys.empty(followerKey(FOLLOWED)))).thenReturn(emptyFr);
        Transaction multi = mock(Transaction.class);
        when(jedis.multi()).thenReturn(multi);

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(multi).srem(followingKey(USER), String.valueOf(FOLLOWED));
        verify(multi).srem(followerKey(FOLLOWED), String.valueOf(USER));
        verify(multi).expire(eq(followingKey(USER)), anyLong());
        verify(multi).expire(eq(followerKey(FOLLOWED)), anyLong());
        verify(multi).exec();
    }

    @Test
    void cacheUnfollowEmptyOrColdKeyInvalidatesPair() {
        Response<Boolean> existsFg = booleanResponse(false);
        Response<Boolean> emptyFg = booleanResponse(true);
        Response<Boolean> existsFr = booleanResponse(true);
        Response<Boolean> emptyFr = booleanResponse(false);
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        when(p.exists(followingKey(USER))).thenReturn(existsFg);
        when(p.exists(CacheKeys.empty(followingKey(USER)))).thenReturn(emptyFg);
        when(p.exists(followerKey(FOLLOWED))).thenReturn(existsFr);
        when(p.exists(CacheKeys.empty(followerKey(FOLLOWED)))).thenReturn(emptyFr);

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(jedis).del(followingKey(USER), CacheKeys.empty(followingKey(USER)),
                followerKey(FOLLOWED), CacheKeys.empty(followerKey(FOLLOWED)));
        verify(jedis, never()).multi();
    }

    @Test
    void cacheUnfollowRedisErrorInvalidatesPair() {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any(Consumer.class));

        cache.cacheUnfollow(USER, FOLLOWED);

        verify(cacheAside).invalidate(followingKey(USER), followerKey(FOLLOWED));
    }
}