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
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        cache = new ContentCache(contentDao, contentMediaDao, tt, cacheAside, redisAccess, singleFlight);

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
        stubGetBatch(Map.of(1L, dto(1L, 1, 2), 2L, dto(2L, 1, 2)));

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertEquals(2, result.size());
        assertEquals(Set.of(1L, 2L), Set.of(result.get(0).getId(), result.get(1).getId()));
        // 索引已存在：不触发懒重建
        verify(singleFlight, never()).get(anyString(), any(Callable.class));
    }

    @Test
    void getRecommendByFilterLazyRebuildsWhenIndexMissing() throws Exception {
        when(jedis.exists("content:index:1:2")).thenReturn(false);
        when(contentDao.findAllContent(conn)).thenReturn(
                List.of(dto(5L, 2, 1)));
        stubIndexScan(Set.of("content:index:1:2"));
        when(singleFlight.get(eq("content:index:rebuild"), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        // 重建后索引有 5
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of("5"));
        stubGetBatch(Map.of(5L, dto(5L, 2, 1)));

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 12);

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getId());
        // 懒重建先 SCAN 删旧索引 key，再写入 4 个索引 key
        verify(jedis).del("content:index:1:2");
        verify(jedis).lpush("content:index:2:1", "5");
        verify(jedis).lpush("content:index:2:-1", "5");
        verify(jedis).lpush("content:index:-1:1", "5");
        verify(jedis).lpush("content:index:-1:-1", "5");
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
    void getRecommendByFilterSkipsNullAndTruncatesToLimit() {
        when(jedis.exists("content:index:1:2")).thenReturn(true);
        when(jedis.lrange("content:index:1:2", 0, -1)).thenReturn(List.of("1", "2", "3"));
        // 1、3 有数据，2 为空标记（null）：批量结果按 shuffle 原序跳过 null、凑满 limit 即断
        Map<Long, ContentCacheDTO> values = new HashMap<>();
        values.put(1L, dto(1L, 1, 2));
        values.put(2L, null);
        values.put(3L, dto(3L, 1, 2));
        stubGetBatch(values);

        List<ContentVO> result = cache.getRecommendByFilter(1, 2, 1);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getId() == 1L || result.get(0).getId() == 3L);
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

    // ==================== init 全量重建（降级不 crash）====================

    @Test
    void initRebuildsContentKeysAndIndexes() throws SQLException {
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, 2, 1)));
        when(contentMediaDao.findMedia(conn, 5L)).thenReturn(videoMediaMap());
        stubIndexScan(Set.of("content:index:2:1"));

        assertDoesNotThrow(() -> cache.init());

        verify(cacheAside).writeOrInvalidate(eq(CacheKeys.content(5L)), any(ContentCacheDTO.class), anyLong());
        verify(jedis).del("content:index:2:1");
        verify(jedis).lpush("content:index:2:1", "5");
    }

    @Test
    void initDbErrorDoesNotCrash() throws SQLException {
        when(contentDao.findAllContent(conn)).thenThrow(new SQLException("db down"));

        assertDoesNotThrow(() -> cache.init());
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