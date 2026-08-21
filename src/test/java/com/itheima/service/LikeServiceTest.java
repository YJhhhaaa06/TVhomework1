package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.CommentLikeDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentLikeDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeServiceTest {

    private ContentDao contentDao;
    private CommentDao commentDao;
    private ContentLikeDao contentLikeDao;
    private CommentLikeDao commentLikeDao;
    private LikeCacheService cache;
    private ContentCacheManager contentCacheManager;
    private TransactionTemplate tt;
    private Connection conn;
    private LikeService service;

    @BeforeEach
    void setUp() throws Exception {
        contentDao = mock(ContentDao.class);
        commentDao = mock(CommentDao.class);
        contentLikeDao = mock(ContentLikeDao.class);
        commentLikeDao = mock(CommentLikeDao.class);
        cache = mock(LikeCacheService.class);
        contentCacheManager = mock(ContentCacheManager.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new LikeService(contentDao, commentDao, contentLikeDao, commentLikeDao,
                cache, contentCacheManager, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    @Test
    void likeContentSuccessUpdatesDbAndCache() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(false);

        service.likeContent(7L, 1L);

        verify(contentLikeDao).addLike(conn, 7L, 1L);
        verify(contentDao).updateLikeCount(conn, 1L, 1);
        verify(cache).likeContent(7L, 1L);
        verify(contentCacheManager).updateContentLikeCount(1L, 1);
    }

    @Test
    void likeContentMissingThrowsNotFound() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.likeContent(7L, 1L));
    }

    @Test
    void likeContentDuplicateThrowsConflict() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.likeContent(7L, 1L));
    }

    @Test
    void likeContentSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(false);
        doThrow(new SQLException("db down")).when(contentLikeDao).addLike(conn, 7L, 1L);

        assertThrows(ServerException.class, () -> service.likeContent(7L, 1L));
    }

    @Test
    void removeLikeContentNotLikedThrowsConflict() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.removeLikeContent(7L, 1L));
    }

    @Test
    void removeLikeContentSuccessUpdatesDbAndCache() throws SQLException {
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentLikeDao.isLiked(conn, 7L, 1L)).thenReturn(true);

        service.removeLikeContent(7L, 1L);

        verify(contentLikeDao).deleteLike(conn, 7L, 1L);
        verify(contentDao).updateLikeCount(conn, 1L, -1);
        verify(cache).unlikeContent(7L, 1L);
        verify(contentCacheManager).updateContentLikeCount(1L, -1);
    }

    @Test
    void likeCommentSuccessUpdatesDbAndCache() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        when(commentLikeDao.isLiked(conn, 7L, 9L)).thenReturn(false);

        service.likeComment(7L, 9L);

        verify(commentLikeDao).addLike(conn, 7L, 9L);
        verify(commentDao).updateLikeCount(conn, 9L, 1);
        verify(cache).likeComment(7L, 9L);
        verify(contentCacheManager).updateCommentLikeCount(9L, 1);
    }

    @Test
    void likeCommentDuplicateThrowsConflict() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        when(commentLikeDao.isLiked(conn, 7L, 9L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.likeComment(7L, 9L));
    }

    @Test
    void isContentLikedCacheHitSkipsDb() {
        when(cache.isContentLiked(7L, 1L)).thenReturn(true);

        assertTrue(service.isContentLiked(7L, 1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void getContentLikeCountCacheHitReturnsDirectly() {
        when(cache.getContentLikeCount(1L)).thenReturn(5);

        assertEquals(5, service.getContentLikeCount(1L));
        verify(tt, never()).execute(any());
    }

    @Test
    void getContentLikeCountCacheMissFillsFromDb() throws SQLException {
        when(cache.getContentLikeCount(1L)).thenReturn(null);
        when(contentLikeDao.findLikerIdsByContentId(conn, 1L)).thenReturn(Set.of(10L, 11L, 12L));

        assertEquals(3, service.getContentLikeCount(1L));
        verify(cache).syncContentLikers(1L, Set.of(10L, 11L, 12L));
    }

    @Test
    void batchIsContentLikedEmptyInputReturnsEmpty() {
        Map<Long, Boolean> result = service.batchIsContentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedAllCachedSkipsDb() {
        when(cache.batchIsContentLiked(7L, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, true, 2L, false));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L));

        assertEquals(true, result.get(1L));
        assertEquals(false, result.get(2L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedPartialMissQueriesAndBackfills() throws SQLException {
        when(cache.batchIsContentLiked(7L, List.of(1L, 2L))).thenReturn(Map.of(1L, true));
        when(contentLikeDao.findLikedContentIds(conn, 7L, List.of(2L))).thenReturn(Set.of(2L));
        when(contentLikeDao.findLikerIdsByContentId(conn, 2L)).thenReturn(Set.of(2L, 3L));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L));

        assertEquals(true, result.get(1L));
        assertEquals(true, result.get(2L));
        verify(cache).syncContentLikers(2L, Set.of(2L, 3L));
    }
}
