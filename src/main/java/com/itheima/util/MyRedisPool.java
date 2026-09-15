package com.itheima.util;

import com.itheima.config.AppConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.time.Duration;

public class MyRedisPool {

    private static final JedisPool pool;

    static {
        JedisPoolConfig config = new JedisPoolConfig();//规则配置对象，告诉连接池配置信息

        config.setMaxTotal(AppConfig.getRedisMaxTotal());     // 最大连接数
        config.setMaxIdle(AppConfig.getRedisMaxIdle());      // 最大空闲连接
        config.setMinIdle(AppConfig.getRedisMinIdle());       // 最小空闲连接
        config.setTestOnBorrow(true); // 取连接时校验
        // T1（cache-01）：池耗尽时 borrow 最多等 1s（默认 -1 = 无限阻塞，Redis 挂时会拖死请求）
        config.setMaxWait(Duration.ofMillis(AppConfig.getRedisPoolMaxWaitMs()));

        // T1（cache-01）：显式配置连接/读写超时（此前走 Jedis 默认 2000ms 且未显式化，治 U-10）。
        // Jedis 5.1.0 无 (poolConfig, host, port, connTimeout, soTimeout) 短构造器，
        // 用 8 参变体 (…, connTimeout, soTimeout, password, database, clientName)，
        // password/clientName=null、database=默认库，保持既有三参构造语义不变
        pool = new JedisPool(config, AppConfig.getRedisHost(), AppConfig.getRedisPort(),
                AppConfig.getRedisConnectTimeoutMs(), AppConfig.getRedisSoTimeoutMs(),
                null, Protocol.DEFAULT_DATABASE, null);
    }

    public static Jedis getJedis() {
        return pool.getResource();
    }

    public static void flushDb() {
        try (Jedis jedis = getJedis()) {
            jedis.flushDB();
        }
    }

    public static void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
