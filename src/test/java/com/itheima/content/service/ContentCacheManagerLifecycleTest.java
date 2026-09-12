package com.itheima.content.service;

import com.itheima.comment.dao.CommentDao;
import com.itheima.like.service.LikeCacheService;
import com.itheima.content.dao.ContentDao;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.exception.CacheException;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.entity.ContentMedia;
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
    void initLoadsContentIntoCache() throws SQLException {
        ContentCacheDTO dto = videoDto();
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());

        ContentCacheManager manager = newManager();
        manager.init();

        ContentCacheDTO cached = manager.getContentFromCache(1L);
        assertNotNull(cached);
        assertEquals("sample", cached.getTitle());
        assertTrue(cached.getVideoUrl().endsWith(".mp4"));
        // 评论树职责已迁入 CommentCache（T3），init 不再全量加载评论
        verify(commentDao, never()).getComments(any(), anyLong());
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

    // ===== 评论区开关（C2）=====
    // 说明（T3）：评论树相关测试（内存构建/增删/归一化）已随职责迁入 CommentCacheTest

    @Test
    void toContentVOCarriesCommentEnabled() throws SQLException {
        ContentCacheDTO dto = videoDto();
        dto.setCommentEnabled(false);
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of());

        ContentCacheManager manager = newManager();
        manager.init();

        assertFalse(manager.toContentVO(dto).isCommentEnabled());
        manager.destroy();
    }

    @Test
    void updateContentCommentEnabledFlipsCachedDto() throws SQLException {
        ContentCacheDTO dto = videoDto();
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap());
        when(commentDao.getComments(conn, 1L)).thenReturn(List.of());

        ContentCacheManager manager = newManager();
        manager.init();
        assertTrue(manager.getContentFromCache(1L).isCommentEnabled());

        manager.updateContentCommentEnabled(1L, false);
        assertFalse(manager.getContentFromCache(1L).isCommentEnabled());
        manager.destroy();
    }
}
