package com.itheima.content.service;

import com.itheima.content.dao.ContentDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.exception.ServerException;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.dto.PageResult;
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

    @BeforeEach
    void setUp() throws Exception {
        followCache = mock(FollowCache.class);
        contentDao = mock(ContentDao.class);
        contentCache = mock(ContentCache.class);
        likeService = mock(LikeService.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new FeedService(followCache, contentDao, contentCache, likeService, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
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
}