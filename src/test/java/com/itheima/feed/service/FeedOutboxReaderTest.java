package com.itheima.feed.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheDomain;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedOutboxReader} 单测（feed2-23 T23）：一次 pipeline 批读多作者 /
 * 三态（命中 / 空标记 / miss）/ miss 回源 + 回填（ZADD + EXPIRE / 空标记）/
 * Redis 降级（DB 回源不写回）/ DB 回源降级（按空处理、**不写空标记**）/ 只碰 feed:outbox:* 键。
 *
 * <p>隔离手法：mock {@link RedisAccess}（回调打到 mock {@link Jedis}，其 {@code pipelined()}
 * 返回 mock {@link Pipeline}，命令返回值用 mock {@link Response} 桩）；
 * mock {@link ContentDao} / {@link CacheAside} / {@link TransactionTemplate}；{@link CacheStats} 用真实件。
 */
class FeedOutboxReaderTest {

    private static final long A = 9L;
    private static final long B = 10L;
    private static final int N = AppConfig.getFeedOutboxWindowSize();
    private static final long TTL = AppConfig.getFeedOutboxTtlSeconds();

    private static Properties originalProps;

    private RedisAccess redis;
    private CacheAside cacheAside;
    private CacheStats stats;
    private ContentDao contentDao;
    private TransactionTemplate transactionTemplate;
    private Jedis jedis;
    private Pipeline pipeline;
    private Connection conn;
    private FeedOutboxReader reader;
    private LogProbe probe;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        replaceProps(originalProps);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisAccess.class);
        cacheAside = mock(CacheAside.class);
        stats = new CacheStats();
        contentDao = mock(ContentDao.class);
        transactionTemplate = mock(TransactionTemplate.class);
        jedis = mock(Jedis.class);
        pipeline = mock(Pipeline.class);
        conn = mock(Connection.class);
        reader = new FeedOutboxReader(redis, cacheAside, stats, contentDao, transactionTemplate);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedOutboxReader.class));

        when(jedis.pipelined()).thenReturn(pipeline);
        doAnswer(inv -> {
            Consumer<Jedis> action = inv.getArgument(0);
            action.accept(jedis);
            return null;
        }).when(redis).executeVoid(any());
        when(transactionTemplate.execute(any(TransactionTemplate.TransactionAction.class)))
                .thenAnswer(inv -> {
                    TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
                    return action.execute(conn);
                });
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    // ==================== 读（一次 pipeline 多作者） ====================

    @Test
    void readsAllAuthorsInSinglePipeline() throws SQLException {
        stubHit(A, List.of(3L, 2L, 1L));
        stubHit(B, List.of(5L));

        List<Long> merged = reader.readOutbox(new LinkedHashSet<>(List.of(A, B)));

        assertEquals(List.of(3L, 2L, 1L, 5L), merged, "各作者内部降序；作者间顺序不影响后段归并");
        verify(jedis, times(1)).pipelined();
        verify(pipeline).zrevrange(CacheKeys.feedOutbox(A), 0L, (long) N - 1);
        verify(pipeline).zrevrange(CacheKeys.feedOutbox(B), 0L, (long) N - 1);
        verify(contentDao, never()).findRecentContentIdsByAuthor(any(), anyList(), anyInt());
    }

    @Test
    void hitReturnsWindowVerbatimAndRenewsTtl() {
        stubHit(A, List.of(9L, 8L));

        assertEquals(List.of(9L, 8L), reader.readOutbox(Set.of(A)));

        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.HIT_DATA));
        // 滑动续期：命中路径也签发 EXPIRE（此处无回填 ⇒ 恰一次）
        verify(pipeline, times(1)).expire(CacheKeys.feedOutbox(A), TTL);
    }

    @Test
    void hitEmptyMarkerReturnsEmptyWithoutDbQuery() throws SQLException {
        stubEmptyMarker(A);

        assertTrue(reader.readOutbox(Set.of(A)).isEmpty());

        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.HIT_EMPTY));
        verify(contentDao, never()).findRecentContentIdsByAuthor(any(), anyList(), anyInt());
        verify(cacheAside, never()).markEmpty(anyString());
    }

    // ==================== miss：批量回源 + 回填 ====================

    @Test
    @SuppressWarnings("unchecked")
    void missLoadsDbInOneBatchAndBackfillsZset() throws SQLException {
        stubMiss(A);
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(A), N))
                .thenReturn(Map.of(A, List.of(3L, 2L)));

        assertEquals(List.of(3L, 2L), reader.readOutbox(Set.of(A)));

        verify(contentDao, times(1)).findRecentContentIdsByAuthor(conn, List.of(A), N);
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.MISS));
        assertEquals(1L, stats.count(CacheDomain.FEED, CacheStats.Event.LOAD));

        ArgumentCaptor<Map<String, Double>> scored = ArgumentCaptor.forClass((Class) Map.class);
        verify(pipeline).zadd(eq(CacheKeys.feedOutbox(A)), scored.capture());
        assertEquals(Map.of("3", 3.0, "2", 2.0), scored.getValue(), "score = contentId（同收件箱口径）");
        // 回填的 EXPIRE 之外，读阶段本身也签了一次（不存在时返回 0、无副作用）
        verify(pipeline, times(2)).expire(CacheKeys.feedOutbox(A), TTL);
        verify(cacheAside, never()).markEmpty(anyString());
    }

    @Test
    void missWithoutContentWritesEmptyMarkerInsteadOfZset() throws SQLException {
        stubMiss(A);
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(A), N))
                .thenReturn(Collections.emptyMap());

        assertTrue(reader.readOutbox(Set.of(A)).isEmpty());

        verify(cacheAside).markEmpty(CacheKeys.feedOutbox(A));
        verify(pipeline, never()).zadd(anyString(), anyMap());
    }

    // ==================== 降级 ====================

    @Test
    void redisDegradeLoadsDbWithoutWriteBack() throws SQLException {
        doThrow(new CacheException("redis down")).when(redis).executeVoid(any());
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(A, B), N))
                .thenReturn(Map.of(A, List.of(3L), B, List.of(5L)));

        List<Long> merged = reader.readOutbox(new LinkedHashSet<>(List.of(A, B)));

        assertEquals(Set.of(3L, 5L), new LinkedHashSet<>(merged));
        verify(contentDao, times(1)).findRecentContentIdsByAuthor(conn, List.of(A, B), N);
        verify(pipeline, never()).zadd(anyString(), anyMap());
        verify(cacheAside, never()).markEmpty(anyString());
        assertEquals(2L, stats.count(CacheDomain.FEED, CacheStats.Event.DEGRADE), "逐作者记降级");
        // Redis 读失败 = 该链唯一捕获点 ⇒ 恰一条带堆栈记录
        LogProbe.assertExactlyOneStacked(probe, Level.WARNING,
                "大V发件箱缓存读失败，降级 DB, authorCount=2", CacheException.class);
    }

    @Test
    void dbFailureOnMissDegradesToEmptyWithoutEmptyMarker() throws SQLException {
        stubMiss(A);
        when(contentDao.findRecentContentIdsByAuthor(conn, List.of(A), N))
                .thenThrow(new SQLException("db down"));

        assertTrue(reader.readOutbox(Set.of(A)).isEmpty(), "回源失败 ⇒ 本次按空处理（不 500）");

        verify(pipeline, never()).zadd(anyString(), anyMap());
        verify(cacheAside, never()).markEmpty(anyString());   // 失败不得固化成"该作者没有内容"
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "大V发件箱 DB 回源失败, authorCount=1", SQLException.class);
        assertEquals(1, probe.atLevel(Level.WARNING).size(), "结论行恰一条（不带栈）");
        assertEquals(null, probe.atLevel(Level.WARNING).getFirst().getThrown());
    }

    // ==================== 键空间红线 ====================

    @Test
    void touchesOnlyFeedOutboxKeys() {
        stubHit(A, List.of(1L));
        stubHit(B, List.of(2L));

        reader.readOutbox(new LinkedHashSet<>(List.of(A, B)));

        ArgumentCaptor<String> existsKeys = ArgumentCaptor.forClass(String.class);
        verify(pipeline, times(4)).exists(existsKeys.capture());   // 每作者：empty: + 数据 key
        ArgumentCaptor<String> rangeKeys = ArgumentCaptor.forClass(String.class);
        verify(pipeline, times(2)).zrevrange(rangeKeys.capture(), eq(0L), eq((long) N - 1));
        ArgumentCaptor<String> expireKeys = ArgumentCaptor.forClass(String.class);
        verify(pipeline, times(2)).expire(expireKeys.capture(), eq(TTL));

        List<String> allKeys = new ArrayList<>(existsKeys.getAllValues());
        allKeys.addAll(rangeKeys.getAllValues());
        allKeys.addAll(expireKeys.getAllValues());
        assertEquals(8, allKeys.size());
        for (String key : allKeys) {
            String dataKey = key.startsWith("empty:") ? key.substring("empty:".length()) : key;
            assertTrue(dataKey.startsWith(CacheKeys.FEED_OUTBOX_PREFIX), "越界 key: " + key);
            assertEquals(CacheDomain.FEED, CacheKeys.domainOf(key), "应归 FEED 域: " + key);
        }
    }

    // ==================== 配置防御（N<=0） ====================

    @Test
    void nonPositiveWindowSizeReturnsEmptyWithoutTouchingRedisOrDb() throws Exception {
        // N<=0 时 `ZREVRANGE 0 N-1` 会退化成读全量 ⇒ 入口取空（与回源侧 limit<=0 返回空同向）
        Properties overridden = new Properties();
        overridden.setProperty("feed.outbox.windowSize", "0");
        replaceProps(overridden);
        try {
            assertTrue(reader.readOutbox(Set.of(A)).isEmpty());

            verify(jedis, never()).pipelined();
            verify(contentDao, never()).findRecentContentIdsByAuthor(any(), anyList(), anyInt());
            assertEquals(1, probe.atLevel(Level.WARNING).size(), "配置非法应留一条结论行");
            assertNull(probe.atLevel(Level.WARNING).getFirst().getThrown(), "结论行不带栈");
        } finally {
            replaceProps(originalProps);
        }
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private static <T> Response<T> response(T value) {
        Response<T> stub = mock(Response.class);
        when(stub.get()).thenReturn(value);
        return stub;
    }

    private static Properties propsField() throws Exception {
        Field f = AppConfig.class.getDeclaredField("PROPS");
        f.setAccessible(true);
        return (Properties) f.get(null);
    }

    private static void replaceProps(Properties p) throws Exception {
        // PROPS 为 static final，不能替换字段引用；改写其 map 内容（先例 AppConfigTest / FeedBigVRouterTest）
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }

    /**
     * 命中数据 key：window 即 {@code ZREVRANGE 0 N-1} 的返回（已降序）。
     *
     * <p>⚠️ Response 桩必须先算成局部变量再进 {@code thenReturn}——Mockito 的 "UnfinishedStubbing"
     * 规则不允许在 {@code when(...)} 尚未收尾时又发起一次新的 {@code when(...)}。
     */
    private void stubHit(long authorId, List<Long> window) {
        String key = CacheKeys.feedOutbox(authorId);
        Response<Boolean> noEmptyMarker = response(false);
        Response<Boolean> dataExists = response(true);
        List<String> raw = new ArrayList<>();
        for (Long id : window) {
            raw.add(String.valueOf(id));
        }
        Response<List<String>> members = response(raw);

        when(pipeline.exists(CacheKeys.empty(key))).thenReturn(noEmptyMarker);
        when(pipeline.exists(key)).thenReturn(dataExists);
        when(pipeline.zrevrange(key, 0L, (long) N - 1)).thenReturn(members);
    }

    /** 命中空标记（该作者确实没内容）。 */
    private void stubEmptyMarker(long authorId) {
        String key = CacheKeys.feedOutbox(authorId);
        Response<Boolean> emptyMarker = response(true);
        Response<Boolean> noData = response(false);

        when(pipeline.exists(CacheKeys.empty(key))).thenReturn(emptyMarker);
        when(pipeline.exists(key)).thenReturn(noData);
    }

    /** 未命中（无空标记、无数据 key）。 */
    private void stubMiss(long authorId) {
        String key = CacheKeys.feedOutbox(authorId);
        Response<Boolean> noEmptyMarker = response(false);
        Response<Boolean> noData = response(false);

        when(pipeline.exists(CacheKeys.empty(key))).thenReturn(noEmptyMarker);
        when(pipeline.exists(key)).thenReturn(noData);
    }
}
