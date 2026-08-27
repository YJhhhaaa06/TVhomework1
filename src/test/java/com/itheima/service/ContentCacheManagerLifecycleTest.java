package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.CacheException;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.ContentMedia;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContentCacheManagerLifecycleTest {

    private ContentDao contentDao;
    private ContentMediaDao contentMediaDao;
    private CommentDao commentDao;
    private LikeCacheService likeCacheService;
    private TransactionTemplate tt;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        contentDao = mock(ContentDao.class);
        contentMediaDao = mock(ContentMediaDao.class);
        commentDao = mock(CommentDao.class);
        likeCacheService = mock(LikeCacheService.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private ContentCacheDTO videoDto() {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(1L);
        dto.setAuthorId(7L);
        dto.setType(1);
        dto.setTitle("sample");
        dto.setDescription("desc");
        dto.setCategoryId(1);
        dto.setCommentCount(2);
        dto.setLikeCount(5);
        dto.setAuthorName("alice");
        return dto;
    }

    private Map<Integer, List<ContentMedia>> mediaMap() {
        return Map.of(
                1, List.of(new ContentMedia(10L, 1L, "/video/1.mp4", 1, 1)),
                3, List.of(new ContentMedia(11L, 1L, "/cover/1.png", 3, 1))
        );
    }

    private ContentCacheManager newManager() {
        return new ContentCacheManager(contentDao, contentMediaDao, commentDao,
                likeCacheService, tt);
    }

    @Test
    void initLoadsContentAndCommentsIntoCache() throws SQLException {
        ContentCacheDTO dto = videoDto();
        CommentCacheDTO comment = new CommentCacheDTO("alice", 1L, 1L, 7L, "hi", null, 0);
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of(comment));

        ContentCacheManager manager = newManager();
        manager.init();

        ContentCacheDTO cached = manager.getContentFromCache(1L);
        assertNotNull(cached);
        assertEquals("sample", cached.getTitle());
        assertTrue(cached.getVideoUrl().endsWith(".mp4"));
        assertEquals(1, manager.getCommentTree(1L).size());
        manager.destroy();
    }

    @Test
    void updateContentLikeCountChangesCachedDto() throws SQLException {
        ContentCacheDTO dto = videoDto();
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of());

        ContentCacheManager manager = newManager();
        manager.init();
        manager.updateContentLikeCount(1L, 1);

        assertEquals(6, dto.getLikeCount());
        manager.destroy();
    }

    @Test
    void destroyShutsDownScheduler() throws Exception {
        ContentCacheDTO dto = videoDto();
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of());

        ContentCacheManager manager = newManager();
        manager.init();

        Field field = ContentCacheManager.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        ScheduledExecutorService scheduler = (ScheduledExecutorService) field.get(manager);
        assertFalse(scheduler.isShutdown());

        manager.destroy();

        assertTrue(scheduler.isShutdown());
    }

    @Test
    void refreshFailureThrowsCacheException() throws SQLException {
        when(contentDao.findAllContent(conn)).thenThrow(new SQLException("db down"));

        ContentCacheManager manager = newManager();

        assertThrows(CacheException.class, manager::refresh);
    }

    @Test
    void removeMainFloorRemovesItsChildrenFromCache() throws SQLException {
        ContentCacheDTO dto = videoDto();
        CommentCacheDTO main = new CommentCacheDTO("alice", 1L, 1L, 7L, "hi", null, 0);
        CommentCacheDTO child = new CommentCacheDTO("bob", 2L, 1L, 8L, "reply", 1L, 0);
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of(main, child));

        ContentCacheManager manager = newManager();
        manager.init();
        manager.removeCommentFromCache(1L, 1L, true);

        assertTrue(manager.getCommentTree(1L).isEmpty());
        manager.destroy();
    }

    @Test
    void removeReplyOnlyRemovesItselfFromCache() throws SQLException {
        ContentCacheDTO dto = videoDto();
        CommentCacheDTO main = new CommentCacheDTO("alice", 1L, 1L, 7L, "hi", null, 0);
        CommentCacheDTO child = new CommentCacheDTO("bob", 2L, 1L, 8L, "reply", 1L, 0);
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of(main, child));

        ContentCacheManager manager = newManager();
        manager.init();
        manager.removeCommentFromCache(1L, 2L, false);

        List<CommentCacheDTO> tree = manager.getCommentTree(1L);
        assertEquals(1, tree.size());
        assertNotNull(tree.get(0).getChildren());
        assertTrue(tree.get(0).getChildren().isEmpty());
        manager.destroy();
    }

    @Test
    void buildCommentTreeNormalizesDeepChainToMainFloor() throws SQLException {
        ContentCacheDTO dto = videoDto();
        CommentCacheDTO main = new CommentCacheDTO("alice", 1L, 1L, 7L, "main", null, 0);
        CommentCacheDTO reply = new CommentCacheDTO("bob", 2L, 1L, 8L, "r1", 1L, 0);
        CommentCacheDTO deepReply = new CommentCacheDTO("carl", 3L, 1L, 9L, "r2", 2L, 0);
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of(main, reply, deepReply));

        ContentCacheManager manager = newManager();
        manager.init();

        List<CommentCacheDTO> tree = manager.getCommentTree(1L);
        assertEquals(1, tree.size());
        assertEquals(2, tree.get(0).getChildren().size(), "回复的回复也应平铺挂到主楼下");
        manager.destroy();
    }
}
