package com.itheima.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9 分域 TTL 配置读取：验证 {@code cache.content/comment/like/follow.ttlMinutes}
 * 四配置键经 AppConfig 四个 getter 正确绑定生效（分域 TTL 取值落地后的接线单测）。
 *
 * <p>期望值直接从 classpath 的 app.properties 解析，与 AppConfig.PROPS 同源比对
 * （不受既有 FileUploadServiceTest / MediaAuditServiceTest 的 PROPS 反射替换影响——
 * 二者仅改 upload.path / media 相关键且 @AfterAll 还原）。
 */
class AppConfigCacheTtlTest {

    private static Long propLong(String key) throws IOException {
        try (InputStream in = AppConfigCacheTtlTest.class.getResourceAsStream("/app.properties")) {
            if (in == null) {
                throw new IOException("classpath 缺少 app.properties");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty(key);
            if (v == null) {
                throw new IOException("app.properties 缺少键: " + key);
            }
            return Long.parseLong(v.trim());
        }
    }

    @Test
    void contentTtlBindToContentTtlMinutes() throws IOException {
        long minutes = propLong("cache.content.ttlMinutes");
        assertTrue(minutes > 0, "cache.content.ttlMinutes 应 > 0");
        assertEquals(minutes * 60 * 1000, AppConfig.getContentTtlMillis());
    }

    @Test
    void commentTtlBindToCommentTtlMinutes() throws IOException {
        long minutes = propLong("cache.comment.ttlMinutes");
        assertTrue(minutes > 0, "cache.comment.ttlMinutes 应 > 0");
        assertEquals(minutes * 60, AppConfig.getCommentTtlSeconds());
    }

    @Test
    void likeTtlBindToLikeTtlMinutes() throws IOException {
        long minutes = propLong("cache.like.ttlMinutes");
        assertTrue(minutes > 0, "cache.like.ttlMinutes 应 > 0");
        assertEquals(minutes * 60, AppConfig.getLikeTtlSeconds());
    }

    @Test
    void followTtlBindToFollowTtlMinutes() throws IOException {
        long minutes = propLong("cache.follow.ttlMinutes");
        assertTrue(minutes > 0, "cache.follow.ttlMinutes 应 > 0");
        assertEquals(minutes * 60, AppConfig.getFollowTtlSeconds());
    }

    @Test
    void fourDomainsBindDistinctiveNonZeroConfigKeys() throws IOException {
        assertTrue(propLong("cache.content.ttlMinutes") > 0);
        assertTrue(propLong("cache.comment.ttlMinutes") > 0);
        assertTrue(propLong("cache.like.ttlMinutes") > 0);
        assertTrue(propLong("cache.follow.ttlMinutes") > 0);
    }
}