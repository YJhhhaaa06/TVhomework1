package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.ioc.annotation.Component;
import com.itheima.util.MyRedisPool;
import redis.clients.jedis.Jedis;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 统一 Redis 访问封装（基于 {@link MyRedisPool}，不改其方法签名）。
 *
 * <p>只负责"取连接 → 执行回调 → 归还"，不新增 Redis 协议方法
 * （get/set/sadd/multi/pipeline 等由业务类在回调内调 {@link Jedis} 原生 API，
 * 为 T4 pipeline、T5 MULTI 双写在同一条连接上执行留口子）。
 *
 * <p>任何 Jedis/连接异常统一包装为 {@link CacheException} 抛出，
 * 由 {@link CacheAside} 或业务类捕获后降级（NEEDS 4.2 缓存必须可降级）。
 */
@Component
public class RedisAccess {

    /**
     * 在单条 Jedis 连接上执行动作并自动归还连接。
     *
     * @param action 返回结果的回调
     * @return 回调返回值
     * @throws CacheException Redis 访问失败时抛出
     */
    public <T> T execute(Function<Jedis, T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null");
        }
        try (Jedis jedis = MyRedisPool.getJedis()) {
            return action.apply(jedis);
        } catch (CacheException e) {
            throw e;
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    /**
     * 在单条 Jedis 连接上执行无返回值动作并自动归还连接。
     */
    public void executeVoid(Consumer<Jedis> action) {
        execute(jedis -> {
            action.accept(jedis);
            return null;
        });
    }

    private CacheException wrap(RuntimeException e) {
        return new CacheException("Redis 访问失败: " + e.getMessage(), e);
    }
}