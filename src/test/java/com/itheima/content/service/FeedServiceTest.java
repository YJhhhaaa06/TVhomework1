package com.itheima.content.service;

import com.itheima.content.dao.ContentDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.exception.ServerException;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.common.model.dto.PageResult;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedServiceTest {

    private FollowCache followCache;
    private ContentDao contentDao;
    private ContentCache contentCache;
    private LikeService likeService;
    private TransactionTemplate tt;
    private Connection conn;
    private FeedService service;
    /** 事务回调执行中标志（T3：断言缓存读发生在事务回调之外）。 */
    private boolean[] inTransaction;

    @BeforeEach
    void setUp() throws Exception {
        followCache = mock(FollowCache.class);
        contentDao = mock(ContentDao.class);
        contentCache = mock(ContentCache.class);
        likeService = mock(LikeService.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        inTransaction = new boolean[1];
        service = new FeedService(followCache, contentDao, contentCache, likeService, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            inTransaction[0] = true;
            try {
                return action.execute(conn);
            } finally {
                inTransaction[0] = false;
            }
        });
        // T8：feed 页内改批量读；默认空映射，用例内自行覆盖
        when(contentCache.getContentsBatch(anyList())).thenReturn(Collections.emptyMap());
    }

    /** getContentsBatch 桩（id → DTO，null 值=缓存 miss 跳过）。 */
    private void stubGetBatch(Map<Long, ContentCacheDTO> values) {
        when(contentCache.getContentsBatch(anyList())).thenReturn(values);
    }

    private ContentCacheDTO dto(long id) {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(id);
        dto.setAuthorId(7L);
        dto.setType(1);
        dto.setTitle("title" + id);
        dto.setCategoryId(1);
        return dto;
    }

    private ContentVO vo(long id) {
        ContentVO vo = new ContentVO();
        vo.setId(id);
        return vo;
    }

    @Test
    void getFeedNoFollowedUsersReturnsEmptyPage() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(Collections.emptyList());

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertTrue(result.getList().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getPageSize());
        verify(contentDao, never()).countContentByUsers(any(), anyList());
    }

    @Test
    void getFeedNoContentReturnsEmptyPage() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(9L));
        when(contentDao.countContentByUsers(conn, List.of(9L))).thenReturn(0);

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertTrue(result.getList().isEmpty());
        assertEquals(0, result.getTotal());
        verify(contentDao, never()).findContentIdsByUsers(any(), anyList(), anyInt(), anyInt());
        // T3：total==0 早退，不触碰缓存（与改造前语义一致）
        verify(contentCache, never()).getContentsBatch(anyList());
    }

    @Test
    void getFeedNormalFillsLikedStatus() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenReturn(2);
        when(contentDao.findContentIdsByUsers(conn, List.of(7L), 0, 10)).thenReturn(List.of(1L, 2L));
        ContentCacheDTO dto1 = dto(1L);
        ContentCacheDTO dto2 = dto(2L);
        stubGetBatch(Map.of(1L, dto1, 2L, dto2));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(contentCache.toContentVO(dto2)).thenReturn(vo(2L));
        when(likeService.batchIsContentLiked(7L, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, true, 2L, false));

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertEquals(2, result.getList().size());
        assertEquals(2, result.getTotal());
        assertEquals(1L, result.getList().get(0).getId());
        assertEquals(2L, result.getList().get(1).getId());
        assertTrue(result.getList().get(0).getIsLiked());
        assertFalse(result.getList().get(1).getIsLiked());
    }

    @Test
    void getFeedSkipsCacheMissAndQueriesLikedForSurvivors() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenReturn(2);
        when(contentDao.findContentIdsByUsers(conn, List.of(7L), 0, 10)).thenReturn(List.of(1L, 2L));
        ContentCacheDTO dto2 = dto(2L);
        // 1 为缓存 miss（null）；Map.of 不允许 null，用 HashMap
        Map<Long, ContentCacheDTO> values = new HashMap<>();
        values.put(1L, null);
        values.put(2L, dto2);
        stubGetBatch(values);
        when(contentCache.toContentVO(dto2)).thenReturn(vo(2L));
        when(likeService.batchIsContentLiked(7L, List.of(2L))).thenReturn(Map.of(2L, true));

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertEquals(1, result.getList().size());
        assertEquals(2L, result.getList().get(0).getId());
        assertTrue(result.getList().get(0).getIsLiked());
    }

    @Test
    void getFeedNullLikedMapLeavesLikedFalse() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenReturn(1);
        when(contentDao.findContentIdsByUsers(conn, List.of(7L), 0, 10)).thenReturn(List.of(1L));
        ContentCacheDTO dto1 = dto(1L);
        stubGetBatch(Map.of(1L, dto1));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(likeService.batchIsContentLiked(7L, List.of(1L))).thenReturn(null);

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertEquals(1, result.getList().size());
        assertFalse(result.getList().get(0).getIsLiked());
    }

    @Test
    void getFeedComputesPaginationOffset() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenReturn(2);
        when(contentDao.findContentIdsByUsers(eq(conn), eq(List.of(7L)), eq(40), eq(20)))
                .thenReturn(List.of(1L));
        ContentCacheDTO dto1 = dto(1L);
        stubGetBatch(Map.of(1L, dto1));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));

        PageResult<ContentVO> result = service.getFeed(7L, 3, 20);

        assertEquals(1, result.getList().size());
        assertEquals(2, result.getTotal());
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(contentDao).findContentIdsByUsers(eq(conn), eq(List.of(7L)), offsetCaptor.capture(), eq(20));
        assertEquals(40, offsetCaptor.getValue());
    }

    @Test
    void getFeedEmptyPageIdsSkipsLikedQuery() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenReturn(2);
        when(contentDao.findContentIdsByUsers(conn, List.of(7L), 40, 10))
                .thenReturn(Collections.emptyList());

        PageResult<ContentVO> result = service.getFeed(7L, 5, 10);

        assertTrue(result.getList().isEmpty());
        assertEquals(2, result.getTotal());
        verify(likeService, never()).batchIsContentLiked(anyLong(), anyList());
    }

    @Test
    void getFeedSqlErrorThrowsServerException() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(7L));
        when(contentDao.countContentByUsers(conn, List.of(7L))).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getFeed(7L, 1, 10));
    }

    /**
     * T3（cache-03，治 N2）：缓存批量读与点赞状态读必须发生在 DB 事务回调**之外**——
     * DAO 查询在回调内（探针自检，防断言空转），缓存读在回调结束后才执行。
     */
    @Test
    void getFeedReadsCachesOutsideDbTransaction() throws SQLException {
        when(followCache.getFollowingIds(7L)).thenReturn(List.of(9L));
        when(contentDao.countContentByUsers(conn, List.of(9L))).thenAnswer(inv -> {
            assertTrue(inTransaction[0], "关注者内容总数查询应在事务回调内执行");
            return 1;
        });
        when(contentDao.findContentIdsByUsers(conn, List.of(9L), 0, 10)).thenAnswer(inv -> {
            assertTrue(inTransaction[0], "页内内容 id 查询应在事务回调内执行");
            return List.of(1L);
        });
        ContentCacheDTO dto1 = dto(1L);
        when(contentCache.getContentsBatch(anyList())).thenAnswer(inv -> {
            assertFalse(inTransaction[0], "内容缓存批量读不应在事务回调内执行");
            return Map.of(1L, dto1);
        });
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(likeService.batchIsContentLiked(7L, List.of(1L))).thenAnswer(inv -> {
            assertFalse(inTransaction[0], "点赞状态缓存读不应在事务回调内执行");
            return Map.of(1L, true);
        });

        PageResult<ContentVO> result = service.getFeed(7L, 1, 10);

        assertEquals(1, result.getList().size());
        assertTrue(result.getList().get(0).getIsLiked());
    }
}