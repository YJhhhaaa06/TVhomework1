package com.itheima.service;

import com.itheima.config.AppConfig;
import com.itheima.controller.UploadType;
import com.itheima.exception.ParamException;
import com.itheima.model.vo.UploadResult;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FileUploadServiceTest {

    @TempDir
    static Path tempDir;

    private static String originalUploadPath;

    private final FileUploadService service = new FileUploadService();

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

    // ===== saveFile =====

    @Test
    void saveFileSuccessWritesViaPartAndReturnsResult() throws Exception {
        Part part = mock(Part.class);
        when(part.getContentType()).thenReturn("video/mp4");
        when(part.getSubmittedFileName()).thenReturn("a.mp4");

        UploadResult result = service.saveFile(part, UploadType.VIDEO);

        assertTrue(result.getUrl().startsWith("/upload/video/"));
        assertTrue(result.getUrl().endsWith(".mp4"));
        String normalized = tempDir.toFile().getAbsolutePath().replace('\\', '/');
        assertTrue(result.getAbsolutePath().startsWith(normalized));
        assertTrue(result.getAbsolutePath().endsWith(".mp4"));
        verify(part).write(anyString());
    }

    @Test
    void saveFileInvalidSuffixThrowsParam() throws Exception {
        Part part = mock(Part.class);
        when(part.getContentType()).thenReturn(null);
        when(part.getSubmittedFileName()).thenReturn("a.exe");

        ParamException ex = assertThrows(ParamException.class, () -> service.saveFile(part, UploadType.VIDEO));
        assertTrue(ex.getMessage().contains("文件类型不支持"));
        verify(part, never()).write(anyString());
    }

    @Test
    void saveFileNullContentTypeRejectsEvenWithValidSuffix() throws Exception {
        // 现状语义：contentType==null 直接拒绝（短路），与后缀是否合法无关
        Part part = mock(Part.class);
        when(part.getContentType()).thenReturn(null);
        when(part.getSubmittedFileName()).thenReturn("a.mp4");

        assertThrows(ParamException.class, () -> service.saveFile(part, UploadType.VIDEO));
        verify(part, never()).write(anyString());
    }

    @Test
    void saveFileInvalidSuffixThrowsEvenWithNonBlankContentType() throws Exception {
        // 现状语义：validate 为 OR 条件——contentType 非空仍须后缀合法，否则同样拒绝
        Part part = mock(Part.class);
        when(part.getContentType()).thenReturn("image/png");
        when(part.getSubmittedFileName()).thenReturn("a.exe");

        assertThrows(ParamException.class, () -> service.saveFile(part, UploadType.VIDEO));
        verify(part, never()).write(anyString());
    }

    // ===== deleteFileQuietly =====

    @Test
    void deleteFileQuietlyNullIsNoOp() {
        assertDoesNotThrow(() -> service.deleteFileQuietly(null));
    }

    @Test
    void deleteFileQuietlyRemovesExistingFile() throws Exception {
        Path file = tempDir.resolve("to-delete.txt");
        Files.write(file, new byte[]{1, 2});

        service.deleteFileQuietly(file.toFile().getAbsolutePath());

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteFileQuietlyMissingFileIsSilent() {
        assertDoesNotThrow(() ->
                service.deleteFileQuietly(tempDir.resolve("not-exist.txt").toString()));
    }

    // ===== deleteFileByUrl =====

    @Test
    void deleteFileByUrlRemovesExistingFile() throws Exception {
        Path videoDir = tempDir.resolve("video");
        Files.createDirectories(videoDir);
        Path file = videoDir.resolve("old.mp4");
        Files.write(file, new byte[]{1});

        service.deleteFileByUrl("/upload/video/old.mp4");

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteFileByUrlInvalidOrTraversalSilentlyIgnores() throws Exception {
        Path videoDir = tempDir.resolve("video");
        Files.createDirectories(videoDir);
        Path safe = videoDir.resolve("safe.mp4");
        Files.write(safe, new byte[]{1});

        // 非法 URL（目录不在白名单）
        assertDoesNotThrow(() -> service.deleteFileByUrl("/upload/bad/x.mp4"));
        // 路径穿越：group2 含 ".." 被拒
        assertDoesNotThrow(() -> service.deleteFileByUrl("/upload/video/a..mp4"));

        assertTrue(Files.exists(safe));
    }
}