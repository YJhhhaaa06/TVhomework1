package com.itheima.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用配置：classpath 加载 app.properties，环境变量覆盖（key 去点转大写，
 * 例：db.password -> DB_PASSWORD；log.file 额外支持 LOG_PATH 别名）。
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
            if (value != null) {
                props.setProperty(key, value.trim());
            }
        }
        return props;
    }

    private static String get(String key) {
        return PROPS.getProperty(key, "").trim();
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

    public static long getContentRefreshMinutes() {
        return getLong("cache.content.refreshMinutes");
    }

    // ===== 日志 =====

    public static String getLogFile() {
        return get("log.file");
    }

    public static String getLogLevel() {
        return get("log.level");
    }
}
