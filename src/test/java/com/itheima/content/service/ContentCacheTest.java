package com.itheima.content.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.content.dao.ContentDao;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.entity.ContentMedia;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentCacheTest {

    private ContentDao contentDao;
    private ContentMediaDao contentMediaDao;
    private CacheAside cacheAside;
    private RedisAccess redisAccess;
    private SingleFlight singleFlight;
    private TransactionTemplate tt;
    private Connection conn;
    private Jedis jedis;
    private ContentCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        contentDao = mock(ContentDao.class);
        contentMediaDao = mock(ContentMediaDao.class);
        cacheAside = mock(CacheAside.class);
        redisAccess = mock(RedisAccess.class);
        singleFlight = mock(SingleFlight.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        jedis = mock(Jedis.class);
        cache = new ContentCache(contentDao, contentMediaDao, tt, cacheAside, redisAccess, singleFlight,
                60_000L); // T5 退避注入：默认实例大冷却（60s），单测试内多次调用落窗口；过期重试用例自建小冷却实例

        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
        // RedisAccess 回调式执行：直接用 mock Jedis 跑真实 lambda
        when(redisAccess.execute(any(Function.class))).thenAnswer(inv -> {
            Function<Jedis, ?> fn = inv.getArgument(0);
            return fn.apply(jedis);
        });
        doAnswer(inv -> {
            Consumer<Jedis> fn = inv.getArgument(0);
            fn.accept(jedis);
            return null;
        }).when(redisAccess).executeVoid(any(Consumer.class));
        // getContent 的 loader：真实执行 DB 装载
        doAnswer(inv -> {
            Callable<ContentCacheDTO> loader = inv.getArgument(2);
            return loader.call();
        }).when(cacheAside).get(anyString(), any(Class.class), any(Callable.class), anyLong());
    }

    /** content:index:* 索引 key 的单次 SCAN 结果桩（cursor="0" 即一次遍历结束）。 */
    private void stubIndexScan(Set<String> keys) {
        when(jedis.scan(anyString(), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("0", new ArrayList<>(keys)));
    }

    /** Pipeline 桩（三期 T5：rebuildIndexes 走 pipeline）。 */
    private Pipeline stubPipeline() {
        Pipeline p = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(p);
        return p;
    }

    /** getBatch 桩（T8：getRecommendByFilter/getContentsBatch 改走批量读，cacheAside 为 mock）。
     * 模拟真实 getBatch 语义：请求的全部 key 都返回（未提供值 → null，等价 hit-empty/加载为空）。 */
    @SuppressWarnings("unchecked")
    private void stubGetBatch(Map<Long, ContentCacheDTO> values) {
        Map<String, ContentCacheDTO> byKey = new HashMap<>();
        for (Map.Entry<Long, ContentCacheDTO> e : values.entrySet()) {
            byKey.put(CacheKeys.content(e.getKey()), e.getValue());
        }
        when(cacheAside.getBatch(anyList(), eq(ContentCacheDTO.class), any(Function.class), anyLong()))
                .thenAnswer(inv -> {
                    List<String> keys = inv.getArgument(0);
                    Map<String, ContentCacheDTO> full = new HashMap<>();
                    for (String k : keys) {
                        full.put(k, byKey.get(k));
                    }
                    return full;
                });
    }

    private ContentCacheDTO dto(long id, int type, int category) {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(id);
        dto.setAuthorId(7L);
        dto.setType(type);
        dto.setTitle("title" + id);
        dto.setCategoryId(category);
        return dto;
    }

    private Map<Integer, List<ContentMedia>> videoMediaMap() {
        return Map.of(
                1, List.of(new ContentMedia(10L, 1L, "/video/1.mp4", 1, 1)),
                3, List.of(new ContentMedia(11L, 1L, "/cover/1.png", 3, 1))
        );
    }

    /** findMediaByContentIds 的扁平行版（三期 T5 批量装载）。 */
    private static List<ContentMedia> videoMediaList(long contentId) {
        return List.of(
                new ContentMedia(10L, contentId, "/video/1.mp4", 1, 1),
                new ContentMedia(11L, contentId, "/cover/1.png", 3, 1)
        );
    }

    // ==================== getContent（三态 Cache-Aside + loader）====================

    @Test
    void getContentLoaderBuildsDtoWithMediaUrls() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L, 1, 2));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(videoMediaMap());

        ContentCacheDTO result = cache.getContent(1L);

        assertNotNull(result);
        assertTrue(result.getVideoUrl().endsWith("/video/1.mp4"));
        assertTrue(result.getCoverUrl().endsWith("/cover/1.png"));
    }

    @Test
    void getContentDbNullReturnsNull() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertNull(cache.getContent(1L));
    }

    @Test
    void getContentSqlErrorThrowsDatabaseException() throws SQLException {
        // 三期 T3：DB 查询失败 = 加载失败（抛 DatabaseException），不再是"确认无数据"→ 不写空标记
        when(contentDao.findContent(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(DatabaseException.class, () -> cache.getContent(1L));
    }

    @Test
    void addContentDbErrorSkipsCacheSyncSilently() throws SQLException {
        // 三期 T3：提交后缓存同步遇 DB 瞬时失败 → 静默跳过（不 500、不写缓存，读自愈）
        when(contentDao.findContent(conn, 5L)).thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> cache.addContent(5L));

        verify(cacheAside, never()).writeOrInvalidate(anyString(), any(ContentCacheDTO.class), anyLong());
        verify(jedis, never()).lpush(anyString(), anyString());
    }

    @Test
    void getContentMediaBrokenReturnsNullWithoutThrowing() throws SQLException {
        // type=1 无视频媒体 → buildContentMedia 抛 NotFoundException，loader 应降级返回 null（不 500）
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L, 1, 2));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(Map.of());

        assertNull(cache.getContent(1L));
    }

    // ==================== getRecommendByFilter（Redis 索引）====================

    @Test
    void getRecommendByFilterReadsIndexAndMapsContent() throws SQLException {
        when(jedis.exists("content:index:1:2")).thenReturn(true);
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of("1", "2"));
        // T5 惰性探测：逐 id 走 getContent（真实 loader 查 DB），不再对全量候选批量探测
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L, 2, 2));
        when(contentDao.findContent(conn, 2L)).thenReturn(dto(2L, 2, 2));
        when(contentMediaDao.findMedia(eq(conn), anyLong())).thenReturn(Map.of());

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertEquals(2, result.size());
        assertEquals(Set.of(1L, 2L), Set.of(result.get(0).getId(), result.get(1).getId()));
        // 索引已存在：不触发懒重建
        verify(singleFlight, never()).get(anyString(), any(Callable.class));
        // 推荐不再走全量批量探测路径
        verify(cacheAside, never()).getBatch(anyList(), any(Class.class), any(Function.class), anyLong());
    }

    @Test
    void getRecommendByFilterLazyRebuildsWhenIndexMissing() throws Exception {
        when(jedis.exists("content:index:1:2")).thenReturn(false);
        when(contentDao.findAllContent(conn)).thenReturn(
                List.of(dto(5L, 2, 1)));
        stubIndexScan(Set.of("content:index:1:2"));
        when(singleFlight.get(eq("content:index:rebuild"), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        Pipeline p = stubPipeline();
        // T5 惰性探测：重建后索引有 5，逐 id getContent 查 DB 装载
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of("5"));
        when(contentDao.findContent(conn, 5L)).thenReturn(dto(5L, 2, 1));
        when(contentMediaDao.findMedia(eq(conn), anyLong())).thenReturn(Map.of());

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getId());
        // 懒重建（三期 T5 pipeline 化）：先 SCAN 收集旧索引 key，再一趟 pipeline 删旧 + 写入 4 个索引 key
        verify(p).del("content:index:1:2");
        verify(p).lpush("content:index:2:1", "5");
        verify(p).lpush("content:index:2:-1", "5");
        verify(p).lpush("content:index:-1:1", "5");
        verify(p).lpush("content:index:-1:-1", "5");
    }

    @Test
    void getRecommendByFilterInvalidTypeThrowsParamException() {
        assertThrows(com.itheima.exception.ParamException.class,
                () -> cache.getRecommendByFilter(3, null, 12));
    }

    // ==================== getContentsBatch / 批量读（T8） ====================

    @Test
    void getContentsBatchMapsKeysAndPassesNulls() {
        // 3 为 null（hit-empty / DB 无数据）；Map.of 不允许 null，用 HashMap
        Map<Long, ContentCacheDTO> values = new HashMap<>();
        values.put(1L, dto(1L, 1, 2));
        values.put(3L, null);
        stubGetBatch(values);

        Map<Long, ContentCacheDTO> result = cache.getContentsBatch(List.of(1L, 2L, 3L));

        // 请求的 3 个 id 全量映射（null 值透传），id 解析正确
        assertEquals(3, result.size());
        assertEquals(1L, result.get(1L).getId());
        assertNull(result.get(2L));
        assertNull(result.get(3L));
    }

    @Test
    void getContentsBatchEmptyInputReturnsEmptyMapWithoutRedis() {
        assertTrue(cache.getContentsBatch(Collections.emptyList()).isEmpty());
        assertTrue(cache.getContentsBatch(null).isEmpty());
        verify(cacheAside, never()).getBatch(anyList(), any(Class.class), any(Function.class), anyLong());
    }

    @Test
    void getRecommendByFilterSkipsNullAndTruncatesToLimit() throws SQLException {
        when(jedis.exists("content:index:1:2")).thenReturn(true);
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of("1", "2", "3"));
        // T5 惰性探测：2 = DB 无数据（null 消耗探测位）；1/3 有数据 → 首个探测到即凑满 limit=1
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L, 2, 2));
        when(contentDao.findContent(conn, 2L)).thenReturn(null);
        when(contentDao.findContent(conn, 3L)).thenReturn(dto(3L, 2, 2));
        when(contentMediaDao.findMedia(eq(conn), anyLong())).thenReturn(Map.of());

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 1);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getId() == 1L || result.get(0).getId() == 3L);
    }

    @Test
    void getRecommendByFilterProbesOnlyUntilLimit() throws SQLException {
        // T5/N2：候选 100 条全部有数据，limit=12 → 惰性探测恰好 12 次，探测量与候选总量解耦
        when(jedis.exists("content:index:1:2")).thenReturn(true);
        List<String> candidates = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            candidates.add(String.valueOf(i));
        }
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(candidates);
        when(contentDao.findContent(eq(conn), anyLong())).thenAnswer(inv -> dto((Long) inv.getArgument(1), 2, 2));
        when(contentMediaDao.findMedia(eq(conn), anyLong())).thenReturn(Map.of());

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertEquals(12, result.size());
        verify(cacheAside, times(12)).get(anyString(), any(Class.class), any(Callable.class), anyLong());
    }

    @Test
    void getRecommendByFilterWorstCaseAllNullProbesAllCandidates() throws SQLException {
        // T5/N2 退化场景：全部候选 DB 无数据（null 消耗探测位）→ 最坏全探测，与现状批量全量探测等价
        when(jedis.exists("content:index:1:2")).thenReturn(true);
        List<String> candidates = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            candidates.add(String.valueOf(i));
        }
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(candidates);
        when(contentDao.findContent(eq(conn), anyLong())).thenReturn(null);

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertTrue(result.isEmpty());
        verify(cacheAside, times(100)).get(anyString(), any(Class.class), any(Callable.class), anyLong());
    }

    // ==================== ensureIndex 懒重建冷却退避（T5/N1）====================

    @Test
    void ensureIndexBacksOffAfterFailedRebuild() throws Exception {
        // T5/N1：重建失败（Redis 写失败）→ 进程内冷却，后续请求不再逐请求触发 DB 全量重建
        when(jedis.exists("content:index:1:2")).thenReturn(false);
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of());
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1)));
        when(singleFlight.get(eq("content:index:rebuild"), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        // rebuildIndexes 的 Redis 写必失败（停机）：executeVoid 抛 CacheException → 重建失败记冷却
        // 注意：此处 doThrow 覆盖 setUp 的 executeVoid doAnswer 桩（重桩合法，未尾调用生效）；
        // 用例内勿再依赖 executeVoid 正常工作（会静默吞错），如需走通须另改桩。
        doThrow(new CacheException("redis down")).when(redisAccess).executeVoid(any(Consumer.class));

        cache.getRecommendByFilter(1, 2, 12); // 第 1 次：exists 失败 → 重建失败 → 记冷却
        cache.getRecommendByFilter(1, 2, 12); // 第 2 次：冷却窗口内 → 跳过探测与重建

        // DB 全表装载只发生 1 次（从"每请求 1 次"收敛到"每冷却窗口 1 次"）
        verify(contentDao, times(1)).findAllContent(any(Connection.class));
    }

    @Test
    void ensureIndexRetriesRebuildAfterCooldownExpiry() throws Exception {
        // T5/N1：冷却过期后下一请求自然重试完整链路（与熔断探针语义同构）
        ContentCache shortCooling = new ContentCache(contentDao, contentMediaDao, tt, cacheAside,
                redisAccess, singleFlight, 30L);
        when(jedis.exists("content:index:1:2")).thenReturn(false);
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of());
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1)));
        when(singleFlight.get(eq("content:index:rebuild"), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        doThrow(new CacheException("redis down")).when(redisAccess).executeVoid(any(Consumer.class));

        shortCooling.getRecommendByFilter(1, 2, 12);
        Thread.sleep(60); // 越过 30ms 冷却窗口
        shortCooling.getRecommendByFilter(1, 2, 12);

        verify(contentDao, times(2)).findAllContent(any(Connection.class));
    }

    // ==================== 写路径 ====================

    @Test
    void addContentWritesContentKeyAndFourIndexKeys() throws SQLException {
        when(contentDao.findContent(conn, 5L)).thenReturn(dto(5L, 2, 1));
        when(contentMediaDao.findMedia(conn, 5L)).thenReturn(videoMediaMap());

        cache.addContent(5L);

        verify(cacheAside).writeOrInvalidate(eq(CacheKeys.content(5L)), any(ContentCacheDTO.class), anyLong());
        verify(jedis).lpush("content:index:2:1", "5");
        verify(jedis).lpush("content:index:2:-1", "5");
        verify(jedis).lpush("content:index:-1:1", "5");
        verify(jedis).lpush("content:index:-1:-1", "5");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addContentIndexWriteFailureDeletesIndexKeysForSelfHeal() throws SQLException {
        // 三期 T4/N4：索引写失败（Redis 抖动）→ best-effort DEL 所属 4 个索引 key，
        // 下次推荐读 ensureIndex 发现缺失即触发既有单飞懒重建全量自愈
        when(contentDao.findContent(conn, 5L)).thenReturn(dto(5L, 2, 1));
        when(contentMediaDao.findMedia(conn, 5L)).thenReturn(videoMediaMap());
        // 第一次调用（写索引）抛 CacheException；第二次（自愈 DEL）直通 mock Jedis
        doThrow(new CacheException("redis blip"))
                .doAnswer(inv -> {
                    Consumer<Jedis> fn = inv.getArgument(0);
                    fn.accept(jedis);
                    return null;
                })
                .when(redisAccess).executeVoid(any());

        assertDoesNotThrow(() -> cache.addContent(5L));

        // content key 写入不受索引失败影响（writeOrInvalidate 正常执行）；4 个索引 key 各被 DEL 一次（自愈触发器）；写入确实失败过（无 lpush）
        verify(cacheAside).writeOrInvalidate(eq(CacheKeys.content(5L)), any(ContentCacheDTO.class), anyLong());
        verify(jedis).del("content:index:2:1");
        verify(jedis).del("content:index:2:-1");
        verify(jedis).del("content:index:-1:1");
        verify(jedis).del("content:index:-1:-1");
        verify(jedis, never()).lpush(anyString(), anyString());
    }

    @Test
    void addContentIndexWriteAndHealDeleteFailureDoesNotThrow() throws SQLException {
        // 三期 T4/N4：Redis 持续挂（写失败 + 自愈 DEL 也失败）→ 双层 best-effort，绝不抛业务异常
        when(contentDao.findContent(conn, 5L)).thenReturn(dto(5L, 2, 1));
        when(contentMediaDao.findMedia(conn, 5L)).thenReturn(videoMediaMap());
        doThrow(new CacheException("redis down")).when(redisAccess).executeVoid(any());

        assertDoesNotThrow(() -> cache.addContent(5L));

        verify(jedis, never()).lpush(anyString(), anyString());
        verify(jedis, never()).del(anyString());
    }

    @Test
    void removeContentInvalidatesContentKeyAndLremAllIndexKeys() {
        stubIndexScan(Set.of("content:index:1:2", "content:index:-1:-1"));

        cache.removeContent(5L);

        verify(cacheAside).invalidate(CacheKeys.content(5L));
        verify(jedis).lrem("content:index:1:2", 0, "5");
        verify(jedis).lrem("content:index:-1:-1", 0, "5");
    }

    @Test
    void removeContentScansMultipleCursorsUntilDone() {
        // SCAN 多游标：首趟返回 cursor="1"+keyA，次趟返回 cursor="0"+keyB → 循环收敛且两 key 均遍历
        when(jedis.scan(anyString(), any(ScanParams.class)))
                .thenReturn(new ScanResult<>("1", List.of("content:index:1:2")),
                        new ScanResult<>("0", List.of("content:index:-1:-1")));

        cache.removeContent(5L);

        verify(jedis).scan(eq("0"), any(ScanParams.class));
        verify(jedis).scan(eq("1"), any(ScanParams.class));
        verify(jedis).lrem("content:index:1:2", 0, "5");
        verify(jedis).lrem("content:index:-1:-1", 0, "5");
    }

    @Test
    void notifyMethodsInvalidateContentKey() {
        cache.notifyLikeCountChanged(5L);
        cache.notifyCommentCountChanged(5L);
        cache.updateCommentEnabled(5L);

        verify(cacheAside, times(3)).invalidate(CacheKeys.content(5L));
    }

    // ==================== init 全量重建（三期 T5：事务外写 + 批量装载 + pipeline） ====================

    @Test
    void initRebuildsContentKeysAndIndexes() throws Exception {
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1)));
        when(contentMediaDao.findMediaByContentIds(eq(conn), anyCollection())).thenReturn(videoMediaList(5L));
        stubIndexScan(Set.of("content:index:2:1"));
        Pipeline p = stubPipeline();

        assertDoesNotThrow(() -> cache.init());

        verify(cacheAside).writeBatch(argThat(m -> m.containsKey((String) CacheKeys.content(5L))), anyLong());
        verify(p).del("content:index:2:1");
        verify(p).lpush("content:index:2:1", "5");
    }

    @Test
    void initMovesRedisWritesOutsideDbTransaction() throws Exception {
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1)));
        when(contentMediaDao.findMediaByContentIds(eq(conn), anyCollection())).thenReturn(videoMediaList(5L));
        stubIndexScan(Set.of("content:index:2:1"));
        stubPipeline();

        cache.init();

        // Redis 写入（writeBatch + 索引重建）必须发生在 DB 事务提交之后：InOrder 断言事务先于缓存写
        InOrder inOrder = inOrder(tt, cacheAside, redisAccess);
        inOrder.verify(tt).execute(any(TransactionTemplate.TransactionAction.class));
        inOrder.verify(cacheAside).writeBatch(anyMap(), anyLong());
        inOrder.verify(redisAccess).executeVoid(any(Consumer.class));
    }

    @Test
    void initLoadsMediaInOneBatchQueryEliminatingNPlusOne() throws Exception {
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1), dto(6L, 1, 3)));
        when(contentMediaDao.findMediaByContentIds(eq(conn), anyCollection()))
                .thenReturn(videoMediaList(5L));
        stubIndexScan(Set.of());
        stubPipeline();

        cache.init();

        // 批量媒体装载恰一次（不逐条 findMedia → N+1 消除）
        verify(contentMediaDao, times(1)).findMediaByContentIds(eq(conn), anyCollection());
        verify(contentMediaDao, never()).findMedia(any(Connection.class), anyLong());
    }

    @Test
    void initRedisRoundTripsConstantForLargeContentCount() throws Exception {
        // 多内容场景：索引重建 executeVoid 恰 1 次（rebuildIndexes 单趟 pipeline）且 execute 为 0；
        // 内容 key 批量写往返（writeBatch 单趟 pipeline）在 CacheAsideTest.writeBatchPipelines 断言，
        // 此处 cacheAside 为 mock 只验证"调用了 writeBatch"——往返次数与内容量解耦
        when(contentDao.findAllContent(conn)).thenReturn(
                List.of(dto(1L, 2, 1), dto(2L, 2, 1), dto(3L, 2, 1)));
        when(contentMediaDao.findMediaByContentIds(eq(conn), anyCollection())).thenReturn(
                List.of());
        stubIndexScan(Set.of());
        stubPipeline();

        cache.init();

        verify(cacheAside).writeBatch(anyMap(), anyLong());
        verify(redisAccess, times(1)).executeVoid(any(Consumer.class));
        verify(redisAccess, times(0)).execute(any(Function.class));
    }

    @Test
    void initSkipsMediaBrokenContentButRebuildsIndexesForIntactOnes() throws Exception {
        // type=1（视频）无视频媒体 → 构建失败（NotFound）→ 跳过 content key 与索引；
        // type=2（图文）正常 → 正常入缓存。断言批量路径的跳过程序（三期 T5 重写）有效
        when(contentDao.findAllContent(conn)).thenReturn(
                List.of(dto(7L, 1, 2), dto(8L, 2, 2)));
        when(contentMediaDao.findMediaByContentIds(eq(conn), anyCollection()))
                .thenReturn(videoMediaList(8L));  // 仅 8 有媒体；7 无视频媒体 → 损坏
        stubIndexScan(Set.of());
        Pipeline p = stubPipeline();

        cache.init();

        // 内容 key 只写完好内容 8（不含损坏内容 7）
        verify(cacheAside).writeBatch(argThat(m ->
                !m.containsKey((String) CacheKeys.content(7L))
                        && m.containsKey((String) CacheKeys.content(8L))), anyLong());
        // 索引只重建完好内容 8
        verify(p).lpush("content:index:2:2", "8");
        verify(p, never()).lpush("content:index:1:2", "7");
    }

    @Test
    void initDbErrorDoesNotCrash() throws Exception {
        when(contentDao.findAllContent(conn)).thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> cache.init());

        // DB 装载失败：不触发任何 Redis 写（缓存走读自愈）
        verify(cacheAside, never()).writeBatch(anyMap(), anyLong());
        verify(redisAccess, never()).executeVoid(any(Consumer.class));
    }

    // ==================== VO 复制 ====================

    @Test
    void toContentVOAndDetailVOcopyFields() {
        ContentCacheDTO dto = dto(1L, 1, 2);
        dto.setCommentCount(3);
        dto.setLikeCount(5);
        dto.setCommentEnabled(true);
        dto.setAuthorName("alice");
        dto.setCoverUrl("/c.png");
        dto.setVideoUrl("/v.mp4");
        dto.setImageUrls(List.of("/i1.jpg"));

        ContentVO vo = cache.toContentVO(dto);
        ContentDetailVO detail = cache.toDetailVO(dto);

        assertEquals(1L, vo.getId());
        assertEquals(3, vo.getCommentCount());
        assertEquals(5, vo.getLikeCount());
        assertEquals("alice", vo.getAuthorName());
        assertEquals("/v.mp4", detail.getVideoUrl());
        assertEquals("/i1.jpg", detail.getImageUrls().get(0));
    }
}