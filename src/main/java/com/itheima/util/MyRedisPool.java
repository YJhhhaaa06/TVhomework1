package com.itheima.util;

import com.itheima.config.AppConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class MyRedisPool {

    private static final JedisPool pool;

    static {
        JedisPoolConfig config = new JedisPoolConfig();//规则配置对象，告诉连接池配置信息

        config.setMaxTotal(AppConfig.getRedisMaxTotal());     // 最大连接数
        config.setMaxIdle(AppConfig.getRedisMaxIdle());      // 最大空闲连接
        config.setMinIdle(AppConfig.getRedisMinIdle());       // 最小空闲连接
        config.setTestOnBorrow(true); // 取连接时校验

        pool = new JedisPool(config, AppConfig.getRedisHost(), AppConfig.getRedisPort());
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
