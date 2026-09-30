package com.itheima.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * feed1-18（T18）：写扩散收件箱 TTL 配置读取——{@code feed.inbox.ttlMinutes} → {@code getFeedInboxTtlSeconds()}；
 * feed2-22（T22）追加窗口重算参数——{@code feed.inbox.windowPerAuthor} → {@code getFeedInboxWindowPerAuthor()}、
 * {@code feed.inbox.windowMax} → {@code getFeedInboxWindowMax()}；
 * feed2-23（T23）追加读侧参数——{@code feed.readWindowMax} → {@code getFeedReadWindowMax()}、
 * {@code feed.outbox.windowSize} → {@code getFeedOutboxWindowSize()}、
 * {@code feed.outbox.ttlMinutes} → {@code getFeedOutboxTtlSeconds()}；
 * feed3-T29 追加 feed 域批量尺寸——{@code feed.fanout.batch} → {@code getFeedFanoutBatch()}、
 * {@code feed.rebuild.authorBatch} → {@code getFeedRebuildAuthorBatch()}（**非正数 fail-fast**——
 * 这两个值分别是 fanout 迭代步长与重建切片步长，0 / 负数会让 fanout 空转、重建死循环）；
 * feed3-T31 追加异步投递参数——{@code feed.delivery.queueCapacity} → {@code getFeedDeliveryQueueCapacity()}、
 * {@code feed.delivery.drainTimeoutMillis} → {@code getFeedDeliveryDrainTimeoutMillis()}（**非正数 fail-fast**——
 * 非正分别等价"投递永远无法入队"与"关停等待无上界"）。
 *
 * <p>两条契约：① 绑定 = classpath `app.properties` 的现值（秒口径 = 分钟 × 60）；
 * ② **键缺失 / 值为空 → 回退默认**（TTL 60 分钟 / K=20 / C=200）——收件箱是真相表的派生读缓存、
 * 窗口参数是离散取值，配置缺失不得让应用起不来（容错口径对齐 `rabbitmq.*` / `log.*` 的"带默认值"一族，
 * 而非 `db.*` / `redis.*` 的 fail-fast）。
 *
 * <p>测试策略沿用既有先例（同为 {@link AppConfigRabbitmqTest} 的 PROPS 反射替换 + `AppConfigCacheTtlTest`
 * 的同源比对）：默认值用例只改 {@code PROPS} 的内容、`finally` 还原，避免污染其它测试类。
 */
class AppConfigFeedInboxTest {

    /** 默认 TTL（分钟），与 AppConfig.getFeedInboxTtlSeconds() 的默认值同源。 */
    private static final long DEFAULT_TTL_MINUTES = 60L;

    /** 默认窗口参数（K / C），与 AppConfig.getFeedInboxWindowPerAuthor/Max() 的默认值同源。 */
    private static final int DEFAULT_WINDOW_PER_AUTHOR = 20;
    private static final int DEFAULT_WINDOW_MAX = 200;

    /** T23 默认值：读侧总窗口 M / 发件箱每作者 N / 发件箱 TTL（分钟）。 */
    private static final int DEFAULT_READ_WINDOW_MAX = 300;
    private static final int DEFAULT_OUTBOX_WINDOW_SIZE = 20;
    private static final long DEFAULT_OUTBOX_TTL_MINUTES = 60L;

    /** T29 默认值：写扩散粉丝窗口批量 / 重建作者批量。 */
    private static final int DEFAULT_FANOUT_BATCH = 200;
    private static final int DEFAULT_REBUILD_AUTHOR_BATCH = 50;

    /** T31 默认值：投递队列容量 / 关停 drain 上界（毫秒）。 */
    private static final int DEFAULT_DELIVERY_QUEUE_CAPACITY = 1000;
    private static final long DEFAULT_DELIVERY_DRAIN_MILLIS = 5000L;

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

    // ===== feed2-22（T22）：窗口重算参数 K / C =====

    @Test
    void windowParamsBoundFromAppProperties() throws IOException {
        assertEquals(Integer.parseInt(propString("feed.inbox.windowPerAuthor")),
                AppConfig.getFeedInboxWindowPerAuthor());
        assertEquals(Integer.parseInt(propString("feed.inbox.windowMax")),
                AppConfig.getFeedInboxWindowMax());
        assertTrue(AppConfig.getFeedInboxWindowPerAuthor() > 0, "K 必须为正（0 会让窗口恒空）");
        assertTrue(AppConfig.getFeedInboxWindowMax() >= AppConfig.getFeedInboxWindowPerAuthor(),
                "C 应不小于 K（否则裁剪必然先于每作者取样生效）");
    }

    @Test
    void windowParamsFallBackToDefaultsWhenKeysMissing() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals(DEFAULT_WINDOW_PER_AUTHOR, AppConfig.getFeedInboxWindowPerAuthor(),
                    "键缺失应回退默认 K=20（可降级；不得 fail-fast）");
            assertEquals(DEFAULT_WINDOW_MAX, AppConfig.getFeedInboxWindowMax(),
                    "键缺失应回退默认 C=200");
        } finally {
            replaceProps(originalProps);
        }
    }

    // ===== feed2-23（T23）：读侧总窗口 M / 发件箱每作者 N / 发件箱 TTL =====

    @Test
    void readSideParamsBoundFromAppProperties() throws IOException {
        assertEquals(Integer.parseInt(propString("feed.readWindowMax")), AppConfig.getFeedReadWindowMax());
        assertEquals(Integer.parseInt(propString("feed.outbox.windowSize")), AppConfig.getFeedOutboxWindowSize());
        assertEquals(Long.parseLong(propString("feed.outbox.ttlMinutes")) * 60,
                AppConfig.getFeedOutboxTtlSeconds(), "TTL 秒口径 = 分钟 × 60");

        assertTrue(AppConfig.getFeedReadWindowMax() > 0, "M 必须为正（0 会让可见窗口恒空）");
        assertTrue(AppConfig.getFeedOutboxWindowSize() > 0, "N 必须为正（0 会让发件箱腿恒空）");
        assertTrue(AppConfig.getFeedOutboxTtlSeconds() > 0, "TTL 必须为正（非正会让回填即过期）");
    }

    @Test
    void readSideParamsFallBackToDefaultsWhenKeysMissing() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals(DEFAULT_READ_WINDOW_MAX, AppConfig.getFeedReadWindowMax(),
                    "键缺失应回退默认 M=300（可降级；不得 fail-fast）");
            assertEquals(DEFAULT_OUTBOX_WINDOW_SIZE, AppConfig.getFeedOutboxWindowSize(),
                    "键缺失应回退默认 N=20");
            assertEquals(DEFAULT_OUTBOX_TTL_MINUTES * 60, AppConfig.getFeedOutboxTtlSeconds(),
                    "键缺失应回退默认 60 分钟");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void blankReadSideKeysFallBackToDefaults() throws Exception {
        Properties blank = new Properties();
        blank.setProperty("feed.readWindowMax", "   ");
        blank.setProperty("feed.outbox.windowSize", "   ");
        blank.setProperty("feed.outbox.ttlMinutes", "   ");
        replaceProps(blank);
        try {
            assertEquals(DEFAULT_READ_WINDOW_MAX, AppConfig.getFeedReadWindowMax());
            assertEquals(DEFAULT_OUTBOX_WINDOW_SIZE, AppConfig.getFeedOutboxWindowSize());
            assertEquals(DEFAULT_OUTBOX_TTL_MINUTES * 60, AppConfig.getFeedOutboxTtlSeconds());
        } finally {
            replaceProps(originalProps);
        }
    }

    /** 三个"窗口"概念分立：读侧 M 与表侧 C 各自独立取键（不得复用同一个键）。 */
    @Test
    void readWindowMaxIsIndependentFromInboxWindowMax() throws IOException {
        String readMax = propString("feed.readWindowMax");
        String inboxMax = propString("feed.inbox.windowMax");

        assertTrue(!readMax.equals(inboxMax),
                "M（读侧总窗口）与 C（表侧窗口）应各自独立：M=" + readMax + ", C=" + inboxMax);
        assertEquals(DEFAULT_READ_WINDOW_MAX, AppConfig.getFeedReadWindowMax());
        assertEquals(DEFAULT_WINDOW_MAX, AppConfig.getFeedInboxWindowMax());
    }

    // ===== feed3-T29：feed 域批量尺寸参数化（写扩散粉丝批量 / 重建作者批量）=====

    @Test
    void batchSizeParamsBoundFromAppProperties() throws IOException {
        assertEquals(Integer.parseInt(propString("feed.fanout.batch")), AppConfig.getFeedFanoutBatch());
        assertEquals(Integer.parseInt(propString("feed.rebuild.authorBatch")),
                AppConfig.getFeedRebuildAuthorBatch());

        assertTrue(AppConfig.getFeedFanoutBatch() > 0, "写扩散批量必须为正（0 会让 fanout 静默空转）");
        assertTrue(AppConfig.getFeedRebuildAuthorBatch() > 0, "重建作者批量必须为正（0 会让切片死循环）");
    }

    @Test
    void batchSizeParamsFallBackToDefaultsWhenKeysMissing() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals(DEFAULT_FANOUT_BATCH, AppConfig.getFeedFanoutBatch(),
                    "键缺失应回退默认 200（可降级；不得 fail-fast）");
            assertEquals(DEFAULT_REBUILD_AUTHOR_BATCH, AppConfig.getFeedRebuildAuthorBatch(),
                    "键缺失应回退默认 50");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void blankBatchSizeKeysFallBackToDefaults() throws Exception {
        Properties blank = new Properties();
        blank.setProperty("feed.fanout.batch", "   ");
        blank.setProperty("feed.rebuild.authorBatch", "   ");
        replaceProps(blank);
        try {
            assertEquals(DEFAULT_FANOUT_BATCH, AppConfig.getFeedFanoutBatch());
            assertEquals(DEFAULT_REBUILD_AUTHOR_BATCH, AppConfig.getFeedRebuildAuthorBatch());
        } finally {
            replaceProps(originalProps);
        }
    }

    /** 非正数 fail-fast：0 / 负数分别是"fanout 静默空转"与"重建切片死循环"的成因。 */
    @Test
    void nonPositiveBatchSizeFailsFast() throws Exception {
        Properties invalid = new Properties();
        invalid.setProperty("feed.fanout.batch", "0");
        invalid.setProperty("feed.rebuild.authorBatch", "-5");
        replaceProps(invalid);
        try {
            assertThrows(IllegalArgumentException.class, AppConfig::getFeedFanoutBatch);
            assertThrows(IllegalArgumentException.class, AppConfig::getFeedRebuildAuthorBatch);
        } finally {
            replaceProps(originalProps);
        }
    }

    // ===== feed 三期异步投递（feed3-T31）=====

    @Test
    void deliveryParamsBoundFromAppProperties() throws IOException {
        assertEquals(Integer.parseInt(propString("feed.delivery.queueCapacity")),
                AppConfig.getFeedDeliveryQueueCapacity());
        assertEquals(Long.parseLong(propString("feed.delivery.drainTimeoutMillis")),
                AppConfig.getFeedDeliveryDrainTimeoutMillis());

        assertTrue(AppConfig.getFeedDeliveryQueueCapacity() > 0,
                "队列容量必须为正（非正 = 投递永远无法入队）");
        assertTrue(AppConfig.getFeedDeliveryDrainTimeoutMillis() > 0,
                "drain 上界必须为正（非正 = 关停等待无上界 / 永不等待）");
    }

    @Test
    void deliveryParamsFallBackToDefaultsWhenKeysMissing() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals(DEFAULT_DELIVERY_QUEUE_CAPACITY, AppConfig.getFeedDeliveryQueueCapacity(),
                    "键缺失应回退默认 1000（可降级；不得 fail-fast）");
            assertEquals(DEFAULT_DELIVERY_DRAIN_MILLIS, AppConfig.getFeedDeliveryDrainTimeoutMillis(),
                    "键缺失应回退默认 5000");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void blankDeliveryKeysFallBackToDefaults() throws Exception {
        Properties blank = new Properties();
        blank.setProperty("feed.delivery.queueCapacity", "   ");
        blank.setProperty("feed.delivery.drainTimeoutMillis", "   ");
        replaceProps(blank);
        try {
            assertEquals(DEFAULT_DELIVERY_QUEUE_CAPACITY, AppConfig.getFeedDeliveryQueueCapacity());
            assertEquals(DEFAULT_DELIVERY_DRAIN_MILLIS, AppConfig.getFeedDeliveryDrainTimeoutMillis());
        } finally {
            replaceProps(originalProps);
        }
    }

    /** 非正数 fail-fast：0 / 负数分别是"投递永远无法入队"与"关停等待无上界"的成因。 */
    @Test
    void nonPositiveDeliveryParamsFailFast() throws Exception {
        Properties invalid = new Properties();
        invalid.setProperty("feed.delivery.queueCapacity", "0");
        invalid.setProperty("feed.delivery.drainTimeoutMillis", "-5");
        replaceProps(invalid);
        try {
            assertThrows(IllegalArgumentException.class, AppConfig::getFeedDeliveryQueueCapacity);
            assertThrows(IllegalArgumentException.class, AppConfig::getFeedDeliveryDrainTimeoutMillis);
        } finally {
            replaceProps(originalProps);
        }
    }

    /** 键存在但非数字 → 照旧抛（fail-fast 家族口径，javadoc 承诺；评审 🟢 补例）。 */
    @Test
    void nonNumericDeliveryParamsFailFast() throws Exception {
        Properties invalid = new Properties();
        invalid.setProperty("feed.delivery.queueCapacity", "abc");
        invalid.setProperty("feed.delivery.drainTimeoutMillis", "1.5");
        replaceProps(invalid);
        try {
            assertThrows(NumberFormatException.class, AppConfig::getFeedDeliveryQueueCapacity);
            assertThrows(NumberFormatException.class, AppConfig::getFeedDeliveryDrainTimeoutMillis);
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
