package com.itheima.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18（N13）配置卫生：AppConfig 带默认值读取重载 + 系统属性覆盖通道 + 默认行为不变的接线单测。
 *
 * <p>测试策略（复用既有先例）：
 * <ul>
 *   <li>默认行为：期望值直接从 classpath 的 app.properties 解析，与 AppConfig.getUploadPath() 同源比对
 *       （同 {@link AppConfigCacheTtlTest} 思路）</li>
 *   <li>系统属性通道：{@code load()} 在类加载时只执行一次，运行期 {@code System.setProperty}
 *       不会进入 PROPS —— 故反射调用私有 {@code load()} 重建 PROPS 替换字段验证（同
 *       {@code FileUploadServiceTest} 的 PROPS 反射替换先例）</li>
 *   <li>带默认值重载为包可见静态方法，同包直接调用</li>
 * </ul>
 * 注意：测试进程环境变量若存在 UPLOAD_PATH 会干扰（env 优先于 sysprop），与既有
 * AppConfigCacheTtlTest 相同的环境假设（测试机无该环境变量残留）。
 */
class AppConfigTest {

    /** 类加载时的原始 PROPS **内容副本**（测试结束后恢复，避免污染其它测试类）。 */
    private static Properties originalProps;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        System.clearProperty("upload.path");
        replaceProps(originalProps);
    }

    // ===== 默认行为不变 =====

    @Test
    void uploadPathDefaultsToAppProperties() throws IOException {
        String fromFile = propString("upload.path");
        assertTrue(fromFile.startsWith("D:/"), "app.properties upload.path 应为绝对路径: " + fromFile);
        assertEquals(fromFile, AppConfig.getUploadPath());
    }

    // ===== 系统属性覆盖通道（T18）=====

    @Test
    void systemPropertyOverridesUploadPath() throws Exception {
        System.setProperty("upload.path", "E:/tmp/verify-media");
        try {
            Properties fresh = invokeLoad();
            replaceProps(fresh);
            assertEquals("E:/tmp/verify-media", AppConfig.getUploadPath());
        } finally {
            System.clearProperty("upload.path");
            replaceProps(originalProps);
        }
    }

    // ===== 带默认值读取重载（T18）=====

    @Test
    void defaultValueUsedWhenKeyMissing() {
        assertEquals("def", AppConfig.get("t18.missing.key", "def"));
        assertEquals(42, AppConfig.getInt("t18.missing.key", 42));
        assertEquals(99L, AppConfig.getLong("t18.missing.key", 99L));
        assertTrue(AppConfig.getBoolean("t18.missing.key", true));
    }

    @Test
    void defaultValueUsedWhenKeyEmpty() throws Exception {
        Properties p = new Properties();
        p.setProperty("t18.empty.key", "   ");
        replaceProps(p);
        try {
            assertEquals("def", AppConfig.get("t18.empty.key", "def"));
            assertEquals(7, AppConfig.getInt("t18.empty.key", 7));
            assertEquals(8L, AppConfig.getLong("t18.empty.key", 8L));
            assertTrue(AppConfig.getBoolean("t18.empty.key", true));
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void existingValueReturnedWithTrim() throws Exception {
        Properties p = new Properties();
        p.setProperty("t18.exist.key", "  abc  ");
        p.setProperty("t18.exist.int", "12");
        p.setProperty("t18.exist.long", "34");
        p.setProperty("t18.exist.bool", "false");
        replaceProps(p);
        try {
            assertEquals("abc", AppConfig.get("t18.exist.key", "def"));
            assertEquals(12, AppConfig.getInt("t18.exist.int", 0));
            assertEquals(34L, AppConfig.getLong("t18.exist.long", 0L));
            assertEquals(false, AppConfig.getBoolean("t18.exist.bool", true));
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void numericParseFailureStillThrows() throws Exception {
        Properties p = new Properties();
        p.setProperty("t18.bad.int", "not-a-number");
        replaceProps(p);
        try {
            // 键存在但解析失败 → 照旧抛（fail-fast，不静默回退默认值）
            assertThrows(NumberFormatException.class, () -> AppConfig.getInt("t18.bad.int", 1));
        } finally {
            replaceProps(originalProps);
        }
    }

    // ===== 工具 =====

    private static String propString(String key) throws IOException {
        try (InputStream in = AppConfigTest.class.getResourceAsStream("/app.properties")) {
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
        // PROPS 为 static final，不能替换字段引用（JDK 17+ 强封装）；改为改写其 map 内容
        // （与 FileUploadServiceTest 的 setProperty 思路一致，@AfterAll/用例 finally 均能还原）。
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }

    private static Properties invokeLoad() throws Exception {
        Method m = AppConfig.class.getDeclaredMethod("load");
        m.setAccessible(true);
        try {
            return (Properties) m.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("调用 AppConfig.load 失败", cause);
        }
    }
}
