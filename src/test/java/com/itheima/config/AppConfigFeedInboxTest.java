package com.itheima.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * feed1-18（T18）：写扩散收件箱 TTL 配置读取——{@code feed.inbox.ttlMinutes} → {@code getFeedInboxTtlSeconds()}。
 *
 * <p>两条契约：① 绑定 = classpath `app.properties` 的现值（秒口径 = 分钟 × 60）；
 * ② **键缺失 / 值为空 → 回退默认 60 分钟**（收件箱是派生副本、可降级，配置缺失不得让应用起不来——
 * 容错口径对齐 `rabbitmq.*` / `log.*` 的"带默认值"一族，而非 `db.*` / `redis.*` 的 fail-fast）。
 *
 * <p>测试策略沿用既有先例（同为 {@link AppConfigRabbitmqTest} 的 PROPS 反射替换 + `AppConfigCacheTtlTest`
 * 的同源比对）：默认值用例只改 {@code PROPS} 的内容、`finally` 还原，避免污染其它测试类。
 */
class AppConfigFeedInboxTest {

    /** 默认 TTL（分钟），与 AppConfig.getFeedInboxTtlSeconds() 的默认值同源。 */
    private static final long DEFAULT_TTL_MINUTES = 60L;

    private static Properties originalProps;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        replaceProps(originalProps);
    }

    @Test
    void ttlBoundFromAppPropertiesAndExpressedInSeconds() throws IOException {
        long minutes = Long.parseLong(propString("feed.inbox.ttlMinutes"));

        assertEquals(minutes * 60, AppConfig.getFeedInboxTtlSeconds());
        assertTrue(AppConfig.getFeedInboxTtlSeconds() > 0, "TTL 必须为正（非正会让收件箱写入即过期）");
    }

    @Test
    void missingKeyFallsBackToDefault() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals(DEFAULT_TTL_MINUTES * 60, AppConfig.getFeedInboxTtlSeconds(),
                    "键缺失应回退默认 60 分钟（可降级；不得 fail-fast）");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void blankKeyFallsBackToDefault() throws Exception {
        Properties blank = new Properties();
        blank.setProperty("feed.inbox.ttlMinutes", "   ");
        replaceProps(blank);
        try {
            assertEquals(DEFAULT_TTL_MINUTES * 60, AppConfig.getFeedInboxTtlSeconds());
        } finally {
            replaceProps(originalProps);
        }
    }

    // ===== 工具（同 AppConfigRabbitmqTest 先例）=====

    private static String propString(String key) throws IOException {
        try (InputStream in = AppConfigFeedInboxTest.class.getResourceAsStream("/app.properties")) {
            if (in == null) {
                throw new IOException("classpath 缺少 app.properties");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty(key);
            if (v == null) {
                throw new IOException("app.properties 缺少键: " + key);
            }
            return v.trim();
        }
    }

    private static Properties propsField() throws Exception {
        Field f = AppConfig.class.getDeclaredField("PROPS");
        f.setAccessible(true);
        return (Properties) f.get(null);
    }

    private static void replaceProps(Properties p) throws Exception {
        // PROPS 为 static final，不能替换字段引用；改为改写其 map 内容（finally 均能还原）
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }
}
