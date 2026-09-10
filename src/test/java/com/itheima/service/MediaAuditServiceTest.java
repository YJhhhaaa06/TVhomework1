package com.itheima.service;

import com.itheima.config.AppConfig;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.model.audit.MediaAuditItem;
import com.itheima.model.audit.MediaAuditResult;
import com.itheima.model.audit.RestoreResult;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.ContentMedia;
import com.itheima.util.TransactionTemplate;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaAuditServiceTest {

    @TempDir
    static Path tempDir;

    private static String originalUploadPath;

    private ContentDao contentDao;
    private ContentMediaDao contentMediaDao;
    private TransactionTemplate tt;
    private Connection conn;
    private MediaAuditService service;

    @BeforeAll
    static void redirectUploadPath() throws Exception {
        Properties props = propsField();
        originalUploadPath = props.getProperty("upload.path");
        props.setProperty("upload.path", tempDir.toString());
    }

    @AfterAll
    static void restoreUploadPath() throws Exception {
        propsField().setProperty("upload.path", originalUploadPath);
    }

    private static Properties propsField() throws Exception {
        Field f = AppConfig.class.getDeclaredField("PROPS");
        f.setAccessible(true);
        return (Properties) f.get(null);
    }

    @BeforeEach
    void setUp() throws Exception {
        contentDao = mock(ContentDao.class);
        contentMediaDao = mock(ContentMediaDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new MediaAuditService(contentDao, contentMediaDao, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private ContentMedia media(long mediaId, long contentId, String url) {
        return new ContentMedia(mediaId, contentId, url, 1, 1);
    }

    private ContentCacheDTO dto(long id, String title) {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(id);
        dto.setTitle(title);
        return dto;
    }

    private String normalizedTempDir() {
        return tempDir.toFile().getAbsolutePath().replace('\\', '/');
    }

    // ===== scanAll =====

    @Test
    void scanAllMixedExistenceAndStatuses() throws Exception {
        Files.createDirectories(tempDir.resolve("video"));
        Files.write(tempDir.resolve("video/exists.mp4"), new byte[]{1});
        when(contentMediaDao.findAllMedia(conn)).thenReturn(List.of(
                media(10L, 1L, "/upload/video/exists.mp4"),
                media(11L, 1L, "/upload/video/missing.mp4"),
                media(12L, 1L, "/upload/bad/x.mp4")));
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(1L, "t1")));

        MediaAuditResult result = service.scanAll();

        assertEquals(3, result.getTotal());
        assertEquals(1, result.getExisting());
        assertEquals(1, result.getMissing());
        assertEquals(1, result.getInvalid());
        List<MediaAuditItem> items = result.getItems();
        assertEquals("EXISTS", items.get(0).getStatus());
        assertEquals("MISSING", items.get(1).getStatus());
        assertEquals("INVALID_URL", items.get(2).getStatus());
        assertEquals("t1", items.get(0).getContentTitle());
        assertTrue(result.getOrphanMediaIds().isEmpty());
        assertTrue(result.getContentsWithoutMedia().isEmpty());
        // 每条 media 独立回写
        verify(contentMediaDao).updateFileExists(eq(conn), eq(10L), eq(true), any(Timestamp.class));
        verify(contentMediaDao).updateFileExists(eq(conn), eq(11L), eq(false), any(Timestamp.class));
        verify(contentMediaDao).updateFileExists(eq(conn), eq(12L), eq(false), any(Timestamp.class));
        // 聚合 flags=[true,false,false] → ok=false
        verify(contentDao).updateFileExists(eq(conn), eq(1L), eq(false), any(Timestamp.class));
    }

    @Test
    void scanAllAllExistingAggregatesOkTrue() throws Exception {
        Files.createDirectories(tempDir.resolve("video"));
        Files.write(tempDir.resolve("video/e1.mp4"), new byte[]{1});
        Files.write(tempDir.resolve("video/e2.mp4"), new byte[]{1});
        when(contentMediaDao.findAllMedia(conn)).thenReturn(List.of(
                media(20L, 1L, "/upload/video/e1.mp4"),
                media(21L, 1L, "/upload/video/e2.mp4")));
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(1L, "t1")));

        MediaAuditResult result = service.scanAll();

        assertEquals(2, result.getExisting());
        assertEquals(0, result.getMissing());
        verify(contentDao).updateFileExists(eq(conn), eq(1L), eq(true), any(Timestamp.class));
    }

    @Test
    void scanAllPureTextContentTreatedComplete() throws Exception {
        when(contentMediaDao.findAllMedia(conn)).thenReturn(Collections.emptyList());
        when(contentDao.findAllContent(conn)).thenReturn(List.of(dto(5L, "txt5")));

        MediaAuditResult result = service.scanAll();

        assertEquals(0, result.getTotal());
        assertEquals(List.of(5L), result.getContentsWithoutMedia());
        verify(contentDao).updateFileExists(eq(conn), eq(5L), eq(true), any(Timestamp.class));
    }

    @Test
    void scanAllOrphanMediaDetected() throws Exception {
        when(contentMediaDao.findAllMedia(conn)).thenReturn(List.of(media(30L, 999L, "/upload/video/x.mp4")));
        when(contentDao.findAllContent(conn)).thenReturn(Collections.emptyList());

        MediaAuditResult result = service.scanAll();

        assertEquals(List.of(30L), result.getOrphanMediaIds());
        assertEquals("MISSING", result.getItems().get(0).getStatus());
        assertNull(result.getItems().get(0).getContentTitle());
        // contentIdSet 为空 → 无 content 聚合回写
        verify(contentDao, never()).updateFileExists(any(), anyLong(), anyBoolean(), any(Timestamp.class));
    }

    @Test
    void scanAllSqlErrorThrowsServerException() throws SQLException {
        when(contentMediaDao.findAllMedia(conn)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.scanAll());
    }

    // ===== restoreMedia =====

    @Test
    void restoreMediaNullPartThrowsParamBeforeTransaction() {
        assertThrows(ParamException.class, () -> service.restoreMedia(11L, null));
        verify(tt, never()).execute(any());
    }

    @Test
    void restoreMediaEmptyPartThrowsParamBeforeTransaction() {
        Part part = mock(Part.class); // getSize 默认 0

        assertThrows(ParamException.class, () -> service.restoreMedia(11L, part));
        verify(tt, never()).execute(any());
    }

    @Test
    void restoreMediaMissingMediaThrowsNotFound() throws SQLException {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(contentMediaDao.findMediaById(conn, 11L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.restoreMedia(11L, part));
        verify(contentMediaDao).findMediaById(conn, 11L);
    }

    @Test
    void restoreMediaInvalidUrlThrowsParam() throws SQLException {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(contentMediaDao.findMediaById(conn, 11L))
                .thenReturn(media(11L, 1L, "/upload/bad/x.mp4"));

        ParamException ex = assertThrows(ParamException.class, () -> service.restoreMedia(11L, part));
        assertTrue(ex.getMessage().contains("URL 不合法"));
    }

    @Test
    void restoreMediaExtMismatchThrowsParam() throws SQLException {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(part.getSubmittedFileName()).thenReturn("b.jpg");
        when(contentMediaDao.findMediaById(conn, 11L))
                .thenReturn(media(11L, 1L, "/upload/video/a.mp4"));

        ParamException ex = assertThrows(ParamException.class, () -> service.restoreMedia(11L, part));
        assertTrue(ex.getMessage().contains("文件扩展名不匹配"));
    }

    @Test
    void restoreMediaSuccessWritesFileAndUpdatesFlags() throws Exception {
        ContentMedia media = media(11L, 1L, "/upload/video/a.mp4");
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(part.getSubmittedFileName()).thenReturn("b.mp4");
        when(part.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 'x'}));
        when(contentMediaDao.findMediaById(conn, 11L)).thenReturn(media);
        when(contentDao.isContentExist(conn, 1L)).thenReturn(true);
        when(contentMediaDao.findMedia(conn, 1L)).thenReturn(Collections.singletonMap(1, List.of(media)));

        RestoreResult result = service.restoreMedia(11L, part);

        assertEquals(11L, result.getMediaId());
        assertEquals("/upload/video/a.mp4", result.getUrl());
        assertTrue(result.getTargetPath().startsWith(normalizedTempDir()));
        assertTrue(result.getTargetPath().endsWith("/video/a.mp4"));
        assertTrue(result.isFileExists());
        assertEquals(1L, result.getSize());
        Path written = tempDir.resolve("video/a.mp4");
        assertTrue(Files.exists(written));
        assertEquals((byte) 'x', Files.readAllBytes(written)[0]);
        verify(contentMediaDao).updateFileExists(eq(conn), eq(11L), eq(true), any(Timestamp.class));
        // 聚合：恢复后文件存在 → content ok=true
        verify(contentDao).updateFileExists(eq(conn), eq(1L), eq(true), any(Timestamp.class));
    }

    @Test
    void restoreMediaOrphanSkipsContentAggregate() throws Exception {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(part.getSubmittedFileName()).thenReturn("b.mp4");
        when(part.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(contentMediaDao.findMediaById(conn, 11L))
                .thenReturn(media(11L, 1L, "/upload/video/a.mp4"));
        when(contentDao.isContentExist(conn, 1L)).thenReturn(false);

        service.restoreMedia(11L, part);

        verify(contentMediaDao).updateFileExists(eq(conn), eq(11L), eq(true), any(Timestamp.class));
        verify(contentMediaDao, never()).findMedia(any(), anyLong());
        verify(contentDao, never()).updateFileExists(any(), anyLong(), anyBoolean(), any(Timestamp.class));
    }

    @Test
    void restoreMediaIoErrorThrowsServerException() throws IOException, SQLException {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(part.getSubmittedFileName()).thenReturn("b.mp4");
        when(part.getInputStream()).thenThrow(new IOException("io"));
        when(contentMediaDao.findMediaById(conn, 11L))
                .thenReturn(media(11L, 1L, "/upload/video/a.mp4"));

        ServerException ex = assertThrows(ServerException.class, () -> service.restoreMedia(11L, part));
        assertTrue(ex.getMessage().contains("文件写入失败"));
    }

    @Test
    void restoreMediaDbErrorThrowsServerException() throws Exception {
        Part part = mock(Part.class);
        when(part.getSize()).thenReturn(10L);
        when(part.getSubmittedFileName()).thenReturn("b.mp4");
        when(part.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(contentMediaDao.findMediaById(conn, 11L))
                .thenReturn(media(11L, 1L, "/upload/video/a.mp4"));
        when(contentDao.isContentExist(conn, 1L)).thenReturn(false);
        doThrow(new SQLException("db down"))
                .when(contentMediaDao).updateFileExists(eq(conn), eq(11L), eq(true), any(Timestamp.class));

        ServerException ex = assertThrows(ServerException.class, () -> service.restoreMedia(11L, part));
        assertTrue(ex.getMessage().contains("数据库更新失败"));
    }
}