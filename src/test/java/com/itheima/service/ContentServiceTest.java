package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentLikeDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.ForbiddenException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.command.UploadCommand;
import com.itheima.model.dto.PageResult;
import com.itheima.model.entity.ContentMedia;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.model.vo.CommentVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentServiceTest {

    private ContentDao contentDao;
    private ContentMediaDao contentMediaDao;
    private CommentDao commentDao;
    private ContentLikeDao contentLikeDao;
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
        commentDao = mock(CommentDao.class);
        contentLikeDao = mock(ContentLikeDao.class);
        commentService = mock(CommentService.class);
        likeService = mock(LikeService.class);
        cache = mock(ContentCacheManager.class);
        filler = mock(ContentStatusFiller.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new ContentService(contentDao, contentMediaDao, commentDao,
                contentLikeDao, commentService, likeService, cache, filler, tt);
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

    // ===== 评论区开关（C2）=====

    @Test
    void setCommentEnabledByAuthorUpdatesDbAndCache() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        service.setCommentEnabled(1L, 7L, false);

        verify(contentDao).updateCommentEnabled(conn, 1L, false);
        verify(cache).updateContentCommentEnabled(1L, false);
    }

    @Test
    void setCommentEnabledByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.setCommentEnabled(1L, 8L, false));
        verify(contentDao, never()).updateCommentEnabled(any(), anyLong(), anyBoolean());
        verify(cache, never()).updateContentCommentEnabled(anyLong(), anyBoolean());
    }

    @Test
    void setCommentEnabledMissingContentThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.setCommentEnabled(1L, 7L, false));
        verify(contentDao, never()).updateCommentEnabled(any(), anyLong(), anyBoolean());
    }

    @Test
    void setCommentEnabledSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentDao.updateCommentEnabled(conn, 1L, false))
                .thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.setCommentEnabled(1L, 7L, false));
        verify(cache, never()).updateContentCommentEnabled(anyLong(), anyBoolean());
    }

    @Test
    void getCommentsForContentReturnsEmptyWhenCommentsDisabled() {
        ContentCacheDTO dto = dto(3L);
        dto.setCommentEnabled(false);
        when(cache.getContentFromCache(3L)).thenReturn(dto);

        List<CommentVO> result = service.getCommentsForContent(3L, null);

        assertTrue(result.isEmpty());
        verify(cache, never()).getCommentTree(anyLong());
        verify(commentService, never()).convertToCommentVOList(anyList(), anyMap());
    }

    // ===== 编辑作品（阶段三）：换源 / 删图 / 改文案 =====

    private ContentMedia media(long mediaId, long contentId, String url, int type, int sort) {
        return new ContentMedia(mediaId, contentId, url, type, sort);
    }

    // ----- replaceMedia -----

    @Test
    void replaceMediaByAuthorUpdatesUrlAndRefreshesCache() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMediaByContentTypeSort(conn, 1L, 3, 1))
                .thenReturn(media(10L, 1L, "/upload/cover/old.png", 3, 1));

        String oldUrl = service.replaceMedia(1L, 7L, 3, 1, "/upload/cover/new.png");

        assertEquals("/upload/cover/old.png", oldUrl);
        verify(contentMediaDao).updateMediaUrl(eq(conn), eq(10L), eq("/upload/cover/new.png"), eq(true), any(Timestamp.class));
        verify(contentDao).updateFileExists(eq(conn), eq(1L), eq(true), any(Timestamp.class));
        verify(cache).refreshContent(1L);
    }

    @Test
    void replaceMediaByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.replaceMedia(1L, 8L, 3, 1, "/upload/cover/new.png"));
        verify(contentMediaDao, never()).updateMediaUrl(any(), anyLong(), anyString(), anyBoolean(), any());
        verify(cache, never()).refreshContent(anyLong());
    }

    @Test
    void replaceMediaMissingContentThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.replaceMedia(1L, 7L, 3, 1, "/upload/cover/new.png"));
    }

    @Test
    void replaceMediaMissingMediaRowThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMediaByContentTypeSort(conn, 1L, 3, 1)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.replaceMedia(1L, 7L, 3, 1, "/upload/cover/new.png"));
    }

    @Test
    void replaceMediaSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMediaByContentTypeSort(conn, 1L, 3, 1))
                .thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.replaceMedia(1L, 7L, 3, 1, "/upload/cover/new.png"));
        verify(cache, never()).refreshContent(anyLong());
    }

    // ----- deleteMedia -----

    @Test
    void deleteMediaNonImageThrowsParamException() throws SQLException {
        assertThrows(ParamException.class, () -> service.deleteMedia(1L, 7L, 1, 1));
        verify(contentMediaDao, never()).deleteMediaByContentIdAndTypeSort(any(), anyLong(), anyInt(), anyInt());
        verify(cache, never()).refreshContent(anyLong());
    }

    @Test
    void deleteMediaImageByAuthorDeletesAndCompactsSort() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMediaByContentTypeSort(conn, 1L, 2, 1))
                .thenReturn(media(11L, 1L, "/upload/image/old.jpg", 2, 1));

        String oldUrl = service.deleteMedia(1L, 7L, 2, 1);

        assertEquals("/upload/image/old.jpg", oldUrl);
        verify(contentMediaDao).deleteMediaByContentIdAndTypeSort(conn, 1L, 2, 1);
        verify(contentMediaDao).compactImageSort(conn, 1L, 1);
        verify(cache).refreshContent(1L);
    }

    @Test
    void deleteMediaByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.deleteMedia(1L, 8L, 2, 1));
        verify(contentMediaDao, never()).deleteMediaByContentIdAndTypeSort(any(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void deleteMediaMissingMediaRowThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMediaByContentTypeSort(conn, 1L, 2, 1)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.deleteMedia(1L, 7L, 2, 1));
    }

    // ----- updateContentInfo -----

    @Test
    void updateContentInfoByAuthorUpdatesAndRefreshesCache() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        service.updateContentInfo(1L, 7L, "新标题", "新简介");

        verify(contentDao).updateContentInfo(conn, 1L, "新标题", "新简介");
        verify(cache).refreshContent(1L);
    }

    @Test
    void updateContentInfoBlankTitleThrowsParamException() throws SQLException {
        assertThrows(ParamException.class, () -> service.updateContentInfo(1L, 7L, "   ", "d"));
        verify(contentDao, never()).updateContentInfo(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void updateContentInfoTooLongTitleThrowsParamException() {
        assertThrows(ParamException.class, () -> service.updateContentInfo(1L, 7L, "字".repeat(51), "d"));
    }

    @Test
    void updateContentInfoTooLongDescriptionThrowsParamException() {
        assertThrows(ParamException.class, () -> service.updateContentInfo(1L, 7L, "t", "字".repeat(5001)));
    }

    @Test
    void updateContentInfoByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.updateContentInfo(1L, 8L, "t", "d"));
        verify(contentDao, never()).updateContentInfo(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void updateContentInfoMissingContentThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.updateContentInfo(1L, 7L, "t", "d"));
    }

    // ===== 删除作品（阶段四 A1）=====

    @Test
    void deleteContentByAuthorCascadesAndEvictsCache() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        // LinkedHashMap 保证按 type 1→3 顺序迭代，与 DAO 真实 ORDER BY type,sort 一致，避免 HashMap 顺序脆性
        Map<Integer, List<ContentMedia>> mediaMap = new LinkedHashMap<>();
        mediaMap.put(1, List.of(media(10L, 1L, "/upload/video/old.mp4", 1, 1)));
        mediaMap.put(3, List.of(media(11L, 1L, "/upload/cover/old.png", 3, 1)));
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(mediaMap);

        List<String> urls = service.deleteContent(1L, 7L);

        assertEquals(List.of("/upload/video/old.mp4", "/upload/cover/old.png"), urls);
        verify(contentDao).softDeleteContent(conn, 1L);
        verify(commentDao).softDeleteByContentId(conn, 1L);
        verify(contentLikeDao).deleteByContentId(conn, 1L);
        verify(contentMediaDao).deleteByContentId(conn, 1L);
        verify(cache).removeContent(1L);
    }

    @Test
    void deleteContentByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.deleteContent(1L, 8L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(commentDao, never()).softDeleteByContentId(any(), anyLong());
        verify(contentLikeDao, never()).deleteByContentId(any(), anyLong());
        verify(contentMediaDao, never()).deleteByContentId(any(), anyLong());
        verify(cache, never()).removeContent(anyLong());
    }

    @Test
    void deleteContentMissingContentThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.deleteContent(1L, 7L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(cache, never()).removeContent(anyLong());
    }

    @Test
    void deleteContentSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMedia(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.deleteContent(1L, 7L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(cache, never()).removeContent(anyLong());
    }
}
