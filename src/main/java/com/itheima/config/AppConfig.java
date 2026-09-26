package com.itheima.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用配置：classpath 加载 app.properties，外部覆盖两级（优先级从高到低）：
 *   1. 环境变量（key 去点转大写，例：db.password -> DB_PASSWORD；log.file 额外支持 LOG_PATH 别名）
 *   2. JVM 系统属性（-Dkey=value，T18：与 Tomcat context.xml 的 ${key:-default} 占位符同源，
 *      保证 /upload 静态挂载与上传落盘两侧一致）
 * 配置文件缺失或解析失败时启动即失败（fail-fast）。
 */
public final class AppConfig {

    private static final Properties PROPS = load();

    private AppConfig() {
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/app.properties")) {
            if (in == null) {
                throw new IllegalStateException("app.properties 不存在于 classpath，无法启动");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载 app.properties 失败", e);
        }

        for (String key : props.stringPropertyNames()) {
            String envName = key.replace('.', '_').toUpperCase();
            String value = System.getenv(envName);
            if (value == null && "log.file".equals(key)) {
                value = System.getenv("LOG_PATH");
            }
            if (value == null) {
                value = System.getProperty(key);   // T18：-Dkey=value 系统属性覆盖（与 context.xml ${key:-default} 同源）
            }
            if (value != null) {
                props.setProperty(key, value.trim());
            }
        }
        return props;
    }

    private static String get(String key) {
        return PROPS.getProperty(key, "").trim();
    }

    // ===== 带默认值读取（T18）：键缺失/值为空时返回默认值；键存在但数值解析失败照旧抛（fail-fast）。
    //      既有无默认值 getter 语义不变。包可见（同包 getter/测试可用）。

    static String get(String key, String defaultValue) {
        String v = PROPS.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? defaultValue : v.trim();
    }

    static int getInt(String key, int defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Integer.parseInt(v);
    }

    static long getLong(String key, long defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Long.parseLong(v);
    }

    static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Boolean.parseBoolean(v);
    }

    private static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    private static long getLong(String key) {
        return Long.parseLong(get(key));
    }

    // ===== 数据库 =====

    public static String getDbDriver() {
        return get("db.driver");
    }

    public static String getDbUrl() {
        return get("db.url");
    }

    public static String getDbUsername() {
        return get("db.username");
    }

    public static String getDbPassword() {
        return get("db.password");
    }

    public static int getDbInitSize() {
        return getInt("db.pool.initSize");
    }

    public static int getDbMaxSize() {
        return getInt("db.pool.maxSize");
    }

    public static long getDbTimeoutMs() {
        return getLong("db.pool.timeoutMs");
    }

    // ===== Redis =====

    public static String getRedisHost() {
        return get("redis.host");
    }

    public static int getRedisPort() {
        return getInt("redis.port");
    }

    public static int getRedisMaxTotal() {
        return getInt("redis.maxTotal");
    }

    public static int getRedisMaxIdle() {
        return getInt("redis.maxIdle");
    }

    public static int getRedisMinIdle() {
        return getInt("redis.minIdle");
    }

    // T1（cache-01）：显式超时 + 熔断参数（治 U-10 未配置超时 / N1 逐请求等连接超时）

    public static int getRedisConnectTimeoutMs() {
        return getInt("redis.connectTimeoutMs");
    }

    public static int getRedisSoTimeoutMs() {
        return getInt("redis.soTimeoutMs");
    }

    public static long getRedisPoolMaxWaitMs() {
        return getLong("redis.pool.maxWaitMs");
    }

    public static int getRedisBreakerFailureThreshold() {
        return getInt("redis.breaker.failureThreshold");
    }

    public static long getRedisBreakerCooldownMillis() {
        return getLong("redis.breaker.cooldownMillis");
    }

    // ===== RabbitMQ（T16 feed1-16：连接基础配置；连接管理/拓扑/消费框架见 T17）=====

    // 与 db.*/redis.* 的 fail-fast 不同，MQ 为"可降级外部依赖"（不可用不得阻断启动），
    // 故统一带默认值读取——键缺失/为空取本地容器约定值（容错口径对齐 log.*）。
    // 默认值与 app.properties 保持一致：本机容器 rabbitmq / 5672（非 RabbitMQ 出厂 guest/guest）。

    public static String getRabbitmqHost() {
        return get("rabbitmq.host", "localhost");
    }

    public static int getRabbitmqPort() {
        return getInt("rabbitmq.port", 5672);
    }

    public static String getRabbitmqUsername() {
        return get("rabbitmq.username", "admin");
    }

    public static String getRabbitmqPassword() {
        return get("rabbitmq.password", "admin123");
    }

    /** AMQP vhost（容器默认 "/"）。 */
    public static String getRabbitmqVhost() {
        return get("rabbitmq.vhost", "/");
    }

    /**
     * 连接 / 握手超时（毫秒，T17 feed1-17）。
     *
     * <p>必须封顶：{@code ConnectionFactory} 默认连接超时 60s，broker 不可用时会把
     * Tomcat 启动线程阻塞 60s，违反"MQ 不可用不得阻断启动"。
     */
    public static int getRabbitmqConnectionTimeoutMs() {
        return getInt("rabbitmq.connection.timeoutMs", 2000);
    }

    // ===== JWT =====

    public static String getJwtSecret() {
        return get("jwt.secret");
    }

    public static long getJwtExpireMillis() {
        return getLong("jwt.expireHours") * 60 * 60 * 1000;
    }

    // ===== 文件上传 =====

    public static String getUploadPath() {
        return get("upload.path");
    }

    public static long getUploadMaxSize() {
        return getLong("upload.maxSize");
    }

    // ===== 缓存 =====

    public static long getContentTtlMillis() {
        return getLong("cache.content.ttlMinutes") * 60 * 1000;
    }

    public static long getCommentTtlSeconds() {
        return getLong("cache.comment.ttlMinutes") * 60;
    }

    public static long getLikeTtlSeconds() {
        return getLong("cache.like.ttlMinutes") * 60;
    }

    public static long getFollowTtlSeconds() {
        return getLong("cache.follow.ttlMinutes") * 60;
    }

    // T5（cache-05）：索引懒重建失败冷却退避窗口（对齐熔断冷却先例 redis.breaker.cooldownMillis）

    public static long getContentIndexRebuildCooldownMillis() {
        return getLong("cache.content.indexRebuildCooldownMillis");
    }

    // ===== IoC =====

    // T17：@Inject 字段取不到 Bean 时是否 fail-fast（键缺失时 get() 返回空串 → 解析为 false；
    // 默认 true 由 app.properties 的 ioc.failFast=true 保证）
    public static boolean getIocFailFast() {
        return Boolean.parseBoolean(get("ioc.failFast"));
    }

    // ===== 日志（T1 log-01：输出端表 + 轮转参数）=====

    // 下列键在 app.properties 中均已给出，此处**仍带默认值**——与 db.*/redis.* 的 fail-fast 不同，
    // 日志配置缺失不得让应用起不来（对齐 LogUtil"某一路输出不可用即降级"的容错口径）。
    // 注意 getInt/get 语义：键**存在但值非法**照旧抛（fail-fast），只有"键缺失或为空"才取默认值。

    public static String getLogFile() {
        return get("log.file");
    }

    public static String getLogLevel() {
        return get("log.level");
    }

    /** 错误输出端文件名：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogErrorFile() {
        return get("log.error.file", "error.log");
    }

    public static String getLogErrorLevel() {
        return get("log.error.level", "SEVERE");
    }

    /** 访问输出端文件名：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogAccessFile() {
        return get("log.access.file", "access.log");
    }

    /** 审计输出端文件名（T8）：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogAuditFile() {
        return get("log.audit.file", "audit.log");
    }

    /** 慢请求阈值（毫秒）：访问日志行 {@code cost >=} 此值 时带 {@code slow=1} 标记（D7 性能观测）。 */
    public static int getLogSlowRequestMs() {
        return getInt("log.slowRequestMs", 1000);
    }

    /** 单文件大小上限（字节）；{@code <=0} 视为不轮转。 */
    public static int getLogMaxBytes() {
        return getInt("log.maxBytes", 10 * 1024 * 1024);
    }

    /** 轮转保留的文件个数（含当前写入文件）。 */
    public static int getLogFileCount() {
        return getInt("log.fileCount", 5);
    }
}
