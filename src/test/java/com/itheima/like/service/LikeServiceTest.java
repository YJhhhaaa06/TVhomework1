package com.itheima.like.service;

import com.itheima.comment.dao.CommentDao;
import com.itheima.like.dao.CommentLikeDao;
import com.itheima.content.dao.ContentDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.content.service.CommentCache;
import com.itheima.content.service.ContentCache;
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
    private ContentCache contentCache;
    private CommentCache commentCache;
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
        contentCache = mock(ContentCache.class);
        commentCache = mock(CommentCache.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new LikeService(contentDao, commentDao, contentLikeDao, commentLikeDao,
                cache, contentCache, commentCache, tt);
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
        verify(contentCache).notifyLikeCountChanged(1L);
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
        verify(contentCache).notifyLikeCountChanged(1L);
    }

    @Test
    void likeCommentSuccessUpdatesDbAndCache() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        when(commentLikeDao.isLiked(conn, 7L, 9L)).thenReturn(false);

        service.likeComment(7L, 9L);

        verify(commentLikeDao).addLike(conn, 7L, 9L);
        verify(commentDao).updateLikeCount(conn, 9L, 1);
        verify(cache).likeComment(7L, 9L);
        verify(commentCache).notifyCommentLikeChanged(9L);
    }

    @Test
    void likeCommentDuplicateThrowsConflict() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        when(commentLikeDao.isLiked(conn, 7L, 9L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.likeComment(7L, 9L));
    }

    @Test
    void isContentLikedDelegatesToCache() {
        when(cache.isContentLiked(7L, 1L)).thenReturn(true);

        assertTrue(service.isContentLiked(7L, 1L));
        verify(cache).isContentLiked(7L, 1L);
        verify(tt, never()).execute(any());
    }

    @Test
    void getContentLikeCountDelegatesToCache() {
        when(cache.getContentLikeCount(1L)).thenReturn(5);

        assertEquals(5, service.getContentLikeCount(1L));
        verify(cache).getContentLikeCount(1L);
        verify(tt, never()).execute(any());
    }

    @Test
    void isCommentLikedDelegatesToCache() {
        when(cache.isCommentLiked(7L, 9L)).thenReturn(false);

        assertFalse(service.isCommentLiked(7L, 9L));
        verify(cache).isCommentLiked(7L, 9L);
    }

    @Test
    void getCommentLikeCountDelegatesToCache() {
        when(cache.getCommentLikeCount(9L)).thenReturn(3);

        assertEquals(3, service.getCommentLikeCount(9L));
        verify(cache).getCommentLikeCount(9L);
    }

    @Test
    void batchIsContentLikedEmptyInputReturnsEmpty() {
        Map<Long, Boolean> result = service.batchIsContentLiked(7L, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedDelegatesToCache() {
        when(cache.batchIsContentLiked(7L, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, true, 2L, false));

        Map<Long, Boolean> result = service.batchIsContentLiked(7L, List.of(1L, 2L));

        assertEquals(true, result.get(1L));
        assertEquals(false, result.get(2L));
        verify(tt, never()).execute(any());
    }

    @Test
    void batchIsContentLikedNullInputReturnsEmpty() {
        Map<Long, Boolean> result = service.batchIsContentLiked(7L, null);

        assertTrue(result.isEmpty());
        verify(cache, never()).batchIsContentLiked(anyLong(), anyList());
    }

    @Test
    void batchIsCommentLikedDelegatesToCache() {
        when(cache.batchIsCommentLiked(7L, List.of(9L))).thenReturn(Map.of(9L, true));

        Map<Long, Boolean> result = service.batchIsCommentLiked(7L, List.of(9L));

        assertEquals(true, result.get(9L));
        verify(cache).batchIsCommentLiked(7L, List.of(9L));
    }
}
