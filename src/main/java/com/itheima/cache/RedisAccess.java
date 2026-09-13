package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
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
 *
 * <p>T1（cache-01）接入全局熔断 {@link RedisCircuitBreaker}：熔断开启时
 * {@link #execute} 不再取连接、立即抛 {@link CacheException} 快速失败
 * （既有 catch 降级路径接住 → 走 DB，不再逐请求等连接超时）；
 * 每次访问成败回填熔断器，探针成功自动恢复正常缓存路径。
 */
@Component
public class RedisAccess {

    private final RedisCircuitBreaker breaker;

    /** IoC 注入构造：全局单例熔断器。 */
    @InjectConstructor
    public RedisAccess(RedisCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    /** 直调构造（既有测试使用）：默认构造全局熔断器（参数读 AppConfig）。 */
    public RedisAccess() {
        this(new RedisCircuitBreaker());
    }

    /**
     * 在单条 Jedis 连接上执行动作并自动归还连接。
     *
     * <p>熔断口径（T1 执行定稿）：从本方法冒出的 {@code CacheException} 记一次熔断失败，
     * 正常返回记一次成功（清零连续失败计数）。
     *
     * @param action 返回结果的回调
     * @return 回调返回值
     * @throws CacheException Redis 访问失败或熔断开启（快速失败）时抛出
     */
    public <T> T execute(Function<Jedis, T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null");
        }
        if (!breaker.tryAcquire()) {
            throw new CacheException("Redis 熔断开启中，快速失败（未访问 Redis）");
        }
        boolean failure = false;
        try (Jedis jedis = MyRedisPool.getJedis()) {
            return action.apply(jedis);
        } catch (CacheException e) {
            failure = true;
            throw e;
        } catch (RuntimeException e) {
            failure = true;
            throw wrap(e);
        } finally {
            if (failure) {
                breaker.recordFailure();
            } else {
                breaker.recordSuccess();
            }
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