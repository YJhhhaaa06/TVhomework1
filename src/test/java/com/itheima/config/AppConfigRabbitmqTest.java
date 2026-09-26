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
 * T16（feed1-16）：RabbitMQ 连接配置读取——{@code rabbitmq.host/port/username/password/vhost}
 * 五键经 AppConfig 五个 getter 绑定生效 + 键缺失/为空回退默认值（MQ 可降级：配置缺失不得让启动失败，
 * 容错口径对齐 log.*）。
 *
 * <p>测试策略（复用既有先例）：
 * <ul>
 *   <li>绑定用例：期望值直接从 classpath 的 app.properties 解析，与 AppConfig getter 同源比对
 *       （同 {@link AppConfigCacheTtlTest} 思路）</li>
 *   <li>默认值用例：沿用 {@link AppConfigTest} 的 PROPS 反射替换先例（static final 字段只改内容，
 *       用例 finally 还原，避免污染其它测试类）</li>
 * </ul>
 * 注意：测试进程环境变量若存在 RABBITMQ_* 会干扰（env 优先于配置文件），与既有 AppConfig 测试
 * 相同的环境假设（测试机无该环境变量残留）。
 */
class AppConfigRabbitmqTest {

    /** 类加载时的原始 PROPS **内容副本**（测试结束后恢复，避免污染其它测试类）。 */
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

    // ===== 绑定：app.properties 键 -> getter =====

    @Test
    void rabbitmqKeysBoundFromAppProperties() throws IOException {
        assertEquals(propString("rabbitmq.host"), AppConfig.getRabbitmqHost());
        assertEquals(Integer.parseInt(propString("rabbitmq.port")), AppConfig.getRabbitmqPort());
        assertEquals(propString("rabbitmq.username"), AppConfig.getRabbitmqUsername());
        assertEquals(propString("rabbitmq.password"), AppConfig.getRabbitmqPassword());
        assertEquals(propString("rabbitmq.vhost"), AppConfig.getRabbitmqVhost());
    }

    @Test
    void portFollowsContainerConvention() throws IOException {
        int port = Integer.parseInt(propString("rabbitmq.port"));
        assertTrue(port > 0 && port <= 65535, "rabbitmq.port 应为合法端口: " + port);
        assertEquals(5672, port, "本机容器约定 = rabbitmq / 5672（T16）");
    }

    // ===== 默认值：键缺失 / 为空（MQ 可降级，不走 db.*/redis.* 的 fail-fast）=====

    @Test
    void missingKeysFallBackToContainerDefaults() throws Exception {
        replaceProps(new Properties());
        try {
            assertEquals("localhost", AppConfig.getRabbitmqHost());
            assertEquals(5672, AppConfig.getRabbitmqPort());
            assertEquals("admin", AppConfig.getRabbitmqUsername());
            assertEquals("admin123", AppConfig.getRabbitmqPassword());
            assertEquals("/", AppConfig.getRabbitmqVhost());
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void blankKeysFallBackToDefaults() throws Exception {
        Properties blank = new Properties();
        blank.setProperty("rabbitmq.host", "   ");
        blank.setProperty("rabbitmq.port", " ");
        replaceProps(blank);
        try {
            assertEquals("localhost", AppConfig.getRabbitmqHost());
            assertEquals(5672, AppConfig.getRabbitmqPort());
            assertEquals("/", AppConfig.getRabbitmqVhost());
        } finally {
            replaceProps(originalProps);
        }
    }

    // ===== 工具 =====

    private static String propString(String key) throws IOException {
        try (InputStream in = AppConfigRabbitmqTest.class.getResourceAsStream("/app.properties")) {
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
        // （与 AppConfigTest / FileUploadServiceTest 的既有先例一致，finally 均能还原）。
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }
}