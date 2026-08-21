package com.itheima.service;

import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.ServerException;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.command.UploadCommand;
import com.itheima.model.dto.PageResult;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.model.vo.CommentVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentServiceTest {

    private ContentDao contentDao;
    private ContentMediaDao contentMediaDao;
    private CommentService commentService;
    private LikeService likeService;
    private ContentCacheManager cache;
    private ContentStatusFiller filler;
    private TransactionTemplate tt;
    private Connection conn;
    private ContentService service;

    @BeforeEach
    void setUp() throws Exception {
        contentDao = mock(ContentDao.class);
        contentMediaDao = mock(ContentMediaDao.class);
        commentService = mock(CommentService.class);
        likeService = mock(LikeService.class);
        cache = mock(ContentCacheManager.class);
        filler = mock(ContentStatusFiller.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new ContentService(contentDao, contentMediaDao, commentService,
                likeService, cache, filler, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
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

    @Test
    void searchAssemblesPageResultAndFillsStatus() throws SQLException {
        ContentCacheDTO dto1 = dto(1L);
        ContentCacheDTO dto2 = dto(2L);
        when(contentDao.countKeywordSearch(conn, "java")).thenReturn(2);
        when(contentDao.keywordSearchInBrief(conn, "java", 1, 10)).thenReturn(List.of(1L, 2L));
        when(cache.getContentFromCache(1L)).thenReturn(dto1);
        when(cache.getContentFromCache(2L)).thenReturn(dto2);
        ContentVO vo1 = new ContentVO();
        vo1.setId(1L);
        ContentVO vo2 = new ContentVO();
        vo2.setId(2L);
        when(cache.toContentVO(dto1)).thenReturn(vo1);
        when(cache.toContentVO(dto2)).thenReturn(vo2);

        PageResult<ContentVO> result = service.search("java", 7L, 1, 10);

        assertEquals(2, result.getList().size());
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getPageSize());
        verify(filler).fillLikeAndFollowBatch(result.getList(), 7L);
    }

    @Test
    void searchSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.countKeywordSearch(conn, "java")).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.search("java", 7L, 1, 10));
    }

    @Test
    void getContentDetailVOReturnsNullWhenCacheMiss() {
        when(cache.getContentFromCache(5L)).thenReturn(null);

        assertNull(service.getContentDetailVO(5L, 7L));
        verifyNoInteractions(filler);
    }

    @Test
    void getContentDetailVOWithUserFillsStatus() {
        ContentCacheDTO dto = dto(5L);
        ContentDetailVO detail = new ContentDetailVO();
        detail.setId(5L);
        when(cache.getContentFromCache(5L)).thenReturn(dto);
        when(cache.toDetailVO(dto)).thenReturn(detail);

        ContentDetailVO result = service.getContentDetailVO(5L, 7L);

        assertSame(detail, result);
        verify(filler).fillContentLikeStatus(detail, 5L, 7L);
        verify(filler).fillFollowStatus(detail, 7L);
    }

    @Test
    void getCommentsForContentWithoutUserSkipsLikeQuery() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "hi", null, 0);
        when(cache.getContentFromCache(3L)).thenReturn(dto(3L));
        when(cache.getCommentTree(3L)).thenReturn(List.of(root));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenReturn(List.of(new CommentVO()));

        List<CommentVO> result = service.getCommentsForContent(3L, null);

        assertEquals(1, result.size());
        verify(likeService, never()).batchIsCommentLiked(anyLong(), anyList());
    }

    @Test
    void getCommentsForContentWithUserQueriesLikedMap() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "hi", null, 0);
        when(cache.getContentFromCache(3L)).thenReturn(dto(3L));
        when(cache.getCommentTree(3L)).thenReturn(List.of(root));
        when(cache.collectCommentIds(List.of(root))).thenReturn(List.of(1L));
        when(likeService.batchIsCommentLiked(7L, List.of(1L))).thenReturn(Map.of(1L, true));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenReturn(List.of(new CommentVO()));

        List<CommentVO> result = service.getCommentsForContent(3L, 7L);

        assertEquals(1, result.size());
        verify(likeService).batchIsCommentLiked(7L, List.of(1L));
    }

    @Test
    void getCommentsForContentEmptyTreeReturnsEmpty() {
        when(cache.getContentFromCache(4L)).thenReturn(dto(4L));
        when(cache.getCommentTree(4L)).thenReturn(Collections.emptyList());

        List<CommentVO> result = service.getCommentsForContent(4L, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void addVideoWritesMediaAndUpdatesCache() throws SQLException {
        UploadCommand uc = UploadCommand.asVideo("title", "desc", 7L, 1);
        when(contentDao.addContent(conn, 7L, 1, "title", "desc", 1)).thenReturn(100L);

        long id = service.addVideo(uc, "v.mp4", "c.png");

        assertEquals(100L, id);
        verify(contentMediaDao).addMedia(conn, 100L, "v.mp4", 1, 1);
        verify(contentMediaDao).addMedia(conn, 100L, "c.png", 3, 1);
        verify(cache).updateCacheAfterAdd(conn, 100L);
    }

    @Test
    void addVideoSqlErrorThrowsServerException() throws SQLException {
        UploadCommand uc = UploadCommand.asVideo("title", "desc", 7L, 1);
        when(contentDao.addContent(conn, 7L, 1, "title", "desc", 1))
                .thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.addVideo(uc, "v.mp4", "c.png"));
    }

    @Test
    void addPostWritesCoverImagesAndUpdatesCache() throws SQLException {
        UploadCommand uc = UploadCommand.asPost("title", "desc", 7L, 0);
        when(contentDao.addContent(conn, 7L, 2, "title", "desc", 0)).thenReturn(200L);

        long id = service.addPost(uc, "c.png", List.of("i1.jpg", "i2.jpg"));

        assertEquals(200L, id);
        verify(contentMediaDao).addMedia(conn, 200L, "c.png", 3, 1);
        verify(contentMediaDao).addMedia(conn, 200L, "i1.jpg", 2, 1);
        verify(contentMediaDao).addMedia(conn, 200L, "i2.jpg", 2, 2);
        verify(cache).updateCacheAfterAdd(conn, 200L);
    }

    @Test
    void addPostWithoutCoverSkipsCoverMedia() throws SQLException {
        UploadCommand uc = UploadCommand.asPost("title", "desc", 7L, 0);
        when(contentDao.addContent(conn, 7L, 2, "title", "desc", 0)).thenReturn(200L);

        long id = service.addPost(uc, null, List.of("i1.jpg"));

        assertEquals(200L, id);
        verify(contentMediaDao, never()).addMedia(eq(conn), eq(200L), eq(null), anyInt(), anyInt());
        verify(contentMediaDao).addMedia(conn, 200L, "i1.jpg", 2, 1);
    }
}
