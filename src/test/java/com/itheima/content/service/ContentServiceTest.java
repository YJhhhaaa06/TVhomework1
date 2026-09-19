package com.itheima.content.service;

import com.itheima.comment.dao.CommentDao;
import com.itheima.comment.service.CommentService;
import com.itheima.content.dao.ContentDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.like.service.LikeService;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ForbiddenException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.upload.model.command.UploadCommand;
import com.itheima.content.model.dto.PageResult;
import com.itheima.content.model.entity.ContentMedia;
import com.itheima.admin.model.vo.AdminContentVO;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.model.vo.CommentVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
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
    private ContentCache contentCache;
    private CommentCache commentCache;
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
        contentCache = mock(ContentCache.class);
        commentCache = mock(CommentCache.class);
        filler = mock(ContentStatusFiller.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new ContentService(contentDao, contentMediaDao, commentDao,
                contentLikeDao, commentService, likeService, contentCache, commentCache, filler, tt);
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
        when(contentCache.getContent(1L)).thenReturn(dto1);
        when(contentCache.getContent(2L)).thenReturn(dto2);
        ContentVO vo1 = new ContentVO();
        vo1.setId(1L);
        ContentVO vo2 = new ContentVO();
        vo2.setId(2L);
        when(contentCache.toContentVO(dto1)).thenReturn(vo1);
        when(contentCache.toContentVO(dto2)).thenReturn(vo2);

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
        when(contentCache.getContent(5L)).thenReturn(null);

        assertNull(service.getContentDetailVO(5L, 7L));
        verifyNoInteractions(filler);
    }

    @Test
    void getContentDetailVOWithUserFillsStatus() {
        ContentCacheDTO dto = dto(5L);
        ContentDetailVO detail = new ContentDetailVO();
        detail.setId(5L);
        when(contentCache.getContent(5L)).thenReturn(dto);
        when(contentCache.toDetailVO(dto)).thenReturn(detail);

        ContentDetailVO result = service.getContentDetailVO(5L, 7L);

        assertSame(detail, result);
        verify(filler).fillContentLikeStatus(detail, 5L, 7L);
        verify(filler).fillFollowStatus(detail, 7L);
    }

    @Test
    void getCommentsForContentWithoutUserSkipsLikeQuery() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "hi", null, 0);
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(List.of(root));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenReturn(List.of(new CommentVO()));

        List<CommentVO> result = service.getCommentsForContent(3L, null);

        assertEquals(1, result.size());
        verify(likeService, never()).batchIsCommentLiked(anyLong(), anyList());
    }

    @Test
    void getCommentsForContentWithUserQueriesLikedMap() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "hi", null, 0);
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(List.of(root));
        when(commentCache.collectCommentIds(List.of(root))).thenReturn(List.of(1L));
        when(likeService.batchIsCommentLiked(7L, List.of(1L))).thenReturn(Map.of(1L, true));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenReturn(List.of(new CommentVO()));

        List<CommentVO> result = service.getCommentsForContent(3L, 7L);

        assertEquals(1, result.size());
        verify(likeService).batchIsCommentLiked(7L, List.of(1L));
    }

    @Test
    void getCommentsForContentEmptyTreeReturnsEmpty() {
        when(contentCache.getContent(4L)).thenReturn(dto(4L));
        when(commentCache.getCommentTree(4L)).thenReturn(null);

        List<CommentVO> result = service.getCommentsForContent(4L, null);

        assertTrue(result.isEmpty());
    }

    // ===== T8 评论列表分页（主楼分页 + 楼中楼整树，缺省路径不受影响） =====

    @Test
    void getCommentsForContentPagedFirstPageReturnsWindowAndEnvelope() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(5));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        PageResult<CommentVO> result = service.getCommentsForContent(3L, null, 1, 2);

        assertEquals(5, result.getTotal(), "total = 主楼条数");
        assertEquals(1, result.getPage());
        assertEquals(2, result.getPageSize());
        assertEquals(3, result.getTotalPages());
        assertEquals(List.of(1L, 2L), ids(result.getList()));
    }

    @Test
    void getCommentsForContentPagedLastPageReturnsRemainder() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(5));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        PageResult<CommentVO> result = service.getCommentsForContent(3L, null, 3, 2);

        assertEquals(5, result.getTotal());
        assertEquals(List.of(5L), ids(result.getList()));
    }

    @Test
    void getCommentsForContentPagedOutOfRangeReturnsEmptyButKeepsTotal() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(5));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        PageResult<CommentVO> result = service.getCommentsForContent(3L, null, 4, 2);

        assertTrue(result.getList().isEmpty(), "越界页返回空列表");
        assertEquals(5, result.getTotal(), "越界页仍返回真实 total");
    }

    @Test
    void getCommentsForContentPagedPagesCoverTreeWithoutOverlap() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(5));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        List<Long> collected = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            collected.addAll(ids(service.getCommentsForContent(3L, null, page, 2).getList()));
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), collected, "页间主楼不重不漏且顺序稳定");
    }

    @Test
    void getCommentsForContentPagedKeepsRepliesWholeWithRoot() {
        CommentCacheDTO root = new CommentCacheDTO("u1", 1L, 3L, 11L, "c1", null, 0);
        CommentCacheDTO reply = new CommentCacheDTO("u2", 2L, 3L, 12L, "r1", 1L, 0);
        root.setChildren(new ArrayList<>(List.of(reply)));
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(new ArrayList<>(List.of(root)));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        PageResult<CommentVO> result = service.getCommentsForContent(3L, null, 1, 1);

        assertEquals(1, result.getList().size());
        assertEquals(1, result.getList().get(0).getChildren().size(), "楼中楼随主楼整体返回、不被切");
        assertEquals(2L, result.getList().get(0).getChildren().get(0).getCommentId());
    }

    @Test
    void getCommentsForContentPagedEmptyTreeReturnsEmptyEnvelope() {
        when(contentCache.getContent(4L)).thenReturn(dto(4L));
        when(commentCache.getCommentTree(4L)).thenReturn(null);

        PageResult<CommentVO> result = service.getCommentsForContent(4L, null, 1, 10);

        assertTrue(result.getList().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(0, result.getTotalPages());
    }

    @Test
    void getCommentsForContentPagedDisabledReturnsEmptyEnvelope() {
        ContentCacheDTO disabled = dto(3L);
        disabled.setCommentEnabled(false);
        when(contentCache.getContent(3L)).thenReturn(disabled);

        PageResult<CommentVO> result = service.getCommentsForContent(3L, null, 1, 10);

        assertTrue(result.getList().isEmpty());
        assertEquals(0, result.getTotal());
        verify(commentCache, never()).getCommentTree(anyLong());
    }

    @Test
    void getCommentsForContentPagedQueriesLikedOnlyForPageComments() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(3));
        when(commentCache.collectCommentIds(anyList())).thenAnswer(inv -> {
            List<CommentCacheDTO> given = inv.getArgument(0);
            return ids(given);
        });
        when(likeService.batchIsCommentLiked(eq(7L), anyList())).thenReturn(Map.of(2L, true));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        PageResult<CommentVO> result = service.getCommentsForContent(3L, 7L, 2, 1);

        // 第 2 页只有主楼 2：点赞批量查询只针对该页，不再全树展开
        verify(likeService).batchIsCommentLiked(7L, List.of(2L));
        assertEquals(List.of(2L), ids(result.getList()));
    }

    @Test
    void getCommentsForContentPagedWithoutUserSkipsLikeQuery() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(2));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        service.getCommentsForContent(3L, null, 1, 10);

        verify(likeService, never()).batchIsCommentLiked(anyLong(), anyList());
        verify(commentCache, never()).collectCommentIds(anyList());
    }

    @Test
    void getCommentsForContentPagedInvalidParamsReturnEmptyInsteadOfThrowing() {
        when(contentCache.getContent(3L)).thenReturn(dto(3L));
        when(commentCache.getCommentTree(3L)).thenReturn(roots(3));
        when(commentService.convertToCommentVOList(anyList(), anyMap()))
                .thenAnswer(inv -> voOf(inv.getArgument(0)));

        // Controller 已归一（page≥1、pageSize 1~50）；此处是 Service 层兜底，不应抛 IndexOutOfBounds
        PageResult<CommentVO> zeroPage = service.getCommentsForContent(3L, null, 0, 10);
        assertTrue(zeroPage.getList().isEmpty(), "page=0 应给空页而非抛异常");
        assertEquals(3, zeroPage.getTotal(), "兜底路径仍返回真实 total");

        PageResult<CommentVO> zeroSize = service.getCommentsForContent(3L, null, 1, 0);
        assertTrue(zeroSize.getList().isEmpty(), "pageSize=0 应给空页而非抛异常");
    }

    private static List<CommentCacheDTO> roots(int n) {
        List<CommentCacheDTO> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new CommentCacheDTO("u" + i, i, 3L, 100L + i, "c" + i, null, 0));
        }
        return list;
    }

    private static List<Long> ids(List<? extends CommentCacheDTO> dtos) {
        List<Long> out = new ArrayList<>();
        for (CommentCacheDTO d : dtos) {
            out.add(d.getCommentId());
        }
        return out;
    }

    private static List<CommentVO> voOf(List<CommentCacheDTO> dtos) {
        List<CommentVO> vos = new ArrayList<>();
        for (CommentCacheDTO d : dtos) {
            CommentVO vo = new CommentVO();
            vo.setCommentId(d.getCommentId());
            if (d.getChildren() != null) {
                vo.setChildren(new ArrayList<>(d.getChildren()));
            }
            vos.add(vo);
        }
        return vos;
    }

    @Test
    void addVideoWritesMediaAndUpdatesCache() throws SQLException {
        UploadCommand uc = UploadCommand.asVideo("title", "desc", 7L, 1);
        when(contentDao.addContent(conn, 7L, 1, "title", "desc", 1)).thenReturn(100L);

        long id = service.addVideo(uc, "v.mp4", "c.png");

        assertEquals(100L, id);
        verify(contentMediaDao).addMedia(conn, 100L, "v.mp4", 1, 1);
        verify(contentMediaDao).addMedia(conn, 100L, "c.png", 3, 1);
        verify(contentCache).addContent(100L);
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
        verify(contentCache).addContent(200L);
    }

    @Test
    void addPostWithoutCoverSkipsCoverMedia() throws SQLException {
        UploadCommand uc = UploadCommand.asPost("title", "desc", 7L, 0);
        when(contentDao.addContent(conn, 7L, 2, "title", "desc", 0)).thenReturn(200L);

        long id = service.addPost(uc, null, List.of("i1.jpg"));

        assertEquals(200L, id);
        verify(contentMediaDao, never()).addMedia(eq(conn), eq(200L), eq(null), anyInt(), anyInt());
        verify(contentMediaDao).addMedia(conn, 200L, "i1.jpg", 2, 1);
        verify(contentCache).addContent(200L);
    }

    // ===== 评论区开关（C2）=====

    @Test
    void setCommentEnabledByAuthorUpdatesDbAndCache() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        service.setCommentEnabled(1L, 7L, false);

        verify(contentDao).updateCommentEnabled(conn, 1L, false);
        verify(contentCache).updateCommentEnabled(1L);
    }

    @Test
    void setCommentEnabledByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.setCommentEnabled(1L, 8L, false));
        verify(contentDao, never()).updateCommentEnabled(any(), anyLong(), anyBoolean());
        verify(contentCache, never()).updateCommentEnabled(anyLong());
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
        verify(contentCache, never()).updateCommentEnabled(anyLong());
    }

    @Test
    void getCommentsForContentReturnsEmptyWhenCommentsDisabled() {
        ContentCacheDTO dto = dto(3L);
        dto.setCommentEnabled(false);
        when(contentCache.getContent(3L)).thenReturn(dto);

        List<CommentVO> result = service.getCommentsForContent(3L, null);

        assertTrue(result.isEmpty());
        verify(commentCache, never()).getCommentTree(anyLong());
        verify(commentService, never()).convertToCommentVOList(anyList(), anyMap());
    }

    @Test
    void getCommentsForContentReturnsEmptyWhenContentMissing() {
        // 隐藏/删除内容 contentCache.getContent 返回 null（4.5 读评论前先确认 content 存在），
        // 直接短路，不触碰评论缓存，防隐藏内容评论泄漏
        when(contentCache.getContent(3L)).thenReturn(null);

        List<CommentVO> result = service.getCommentsForContent(3L, null);

        assertTrue(result.isEmpty());
        verify(commentCache, never()).getCommentTree(anyLong());
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
        verify(contentCache).refreshContent(1L);
    }

    @Test
    void replaceMediaByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.replaceMedia(1L, 8L, 3, 1, "/upload/cover/new.png"));
        verify(contentMediaDao, never()).updateMediaUrl(any(), anyLong(), anyString(), anyBoolean(), any());
        verify(contentCache, never()).refreshContent(anyLong());
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
        verify(contentCache, never()).refreshContent(anyLong());
    }

    // ----- deleteMedia -----

    @Test
    void deleteMediaNonImageThrowsParamException() throws SQLException {
        assertThrows(ParamException.class, () -> service.deleteMedia(1L, 7L, 1, 1));
        verify(contentMediaDao, never()).deleteMediaByContentIdAndTypeSort(any(), anyLong(), anyInt(), anyInt());
        verify(contentCache, never()).refreshContent(anyLong());
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
        verify(contentCache).refreshContent(1L);
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
        verify(contentCache).refreshContent(1L);
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
        verify(contentCache).removeContent(1L);
        verify(commentCache).invalidateComments(1L);
        verify(likeService).deleteContentLike(1L);
    }

    @Test
    void deleteContentByOtherThrowsForbidden() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));

        assertThrows(ForbiddenException.class, () -> service.deleteContent(1L, 8L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(commentDao, never()).softDeleteByContentId(any(), anyLong());
        verify(contentLikeDao, never()).deleteByContentId(any(), anyLong());
        verify(contentMediaDao, never()).deleteByContentId(any(), anyLong());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void deleteContentMissingContentThrowsNotFound() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.deleteContent(1L, 7L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void deleteContentSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.findContent(conn, 1L)).thenReturn(dto(1L));
        when(contentMediaDao.findMedia(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.deleteContent(1L, 7L));
        verify(contentDao, never()).softDeleteContent(any(), anyLong());
        verify(contentCache, never()).removeContent(anyLong());
    }

    // ===== 管理员下架/恢复内容（阶段五 A2）=====

    @Test
    void listContentForAdminReturnsDaoResult() throws SQLException {
        AdminContentVO vo = new AdminContentVO();
        vo.setId(1L);
        vo.setHidden(false);
        when(contentDao.findContentForAdmin(conn)).thenReturn(List.of(vo));

        List<AdminContentVO> result = service.listContentForAdmin();

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        assertFalse(result.get(0).isHidden());
    }

    @Test
    void listContentForAdminSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.findContentForAdmin(conn)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.listContentForAdmin());
    }

    @Test
    void hideContentByAdminSetsState2AndEvictsCache() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(0);

        service.hideContent(1L);

        verify(contentDao).updateContentDeletedState(conn, 1L, 2);
        verify(contentCache).removeContent(1L);
        verify(commentCache).invalidateComments(1L);
        verify(likeService).deleteContentLike(1L);
    }

    @Test
    void hideContentMissingThrowsNotFound() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(-1);

        assertThrows(NotFoundException.class, () -> service.hideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void hideContentAlreadyDeletedThrowsConflict() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(1);

        assertThrows(ConflictException.class, () -> service.hideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void hideContentAlreadyHiddenThrowsConflict() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(2);

        assertThrows(ConflictException.class, () -> service.hideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void hideContentSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.hideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).removeContent(anyLong());
    }

    @Test
    void unhideContentByAdminSetsState0AndRefreshesCache() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(2);

        service.unhideContent(1L);

        verify(contentDao).updateContentDeletedState(conn, 1L, 0);
        verify(contentCache).refreshContent(1L);
    }

    @Test
    void unhideContentMissingThrowsNotFound() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(-1);

        assertThrows(NotFoundException.class, () -> service.unhideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).refreshContent(anyLong());
    }

    @Test
    void unhideContentAlreadyDeletedThrowsConflict() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(1);

        assertThrows(ConflictException.class, () -> service.unhideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).refreshContent(anyLong());
    }

    @Test
    void unhideContentNotHiddenThrowsConflict() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.unhideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).refreshContent(anyLong());
    }

    @Test
    void unhideContentSqlErrorThrowsServerException() throws SQLException {
        when(contentDao.getContentStatus(conn, 1L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.unhideContent(1L));
        verify(contentDao, never()).updateContentDeletedState(any(), anyLong(), anyInt());
        verify(contentCache, never()).refreshContent(anyLong());
    }
}
