package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;

import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Random;

/**
 * 统一 Cache-Aside 封装（NEEDS 4.2/4.3/4.4/4.12）：三态读取 + 空标记独立 key + 写失败=DEL 降级。
 *
 * <p>核心原则（4.2）：缓存仅作加速器，**任何缓存失败不得导致业务失败**——
 * <ul>
 *   <li>读路径：缓存 miss / Redis 挂 → 查 DB 返回（降级，不报错）；</li>
 *   <li>写路径：缓存同步失败 → 失效（DEL）让读自愈，绝不忽略写失败留旧缓存；</li>
 *   <li>降级路径不写回（D4）：Redis 异常时直接返回 DB 结果，写回交给下次 miss 自愈。</li>
 * </ul>
 *
 * <p>三态（4.3）：miss=key 不存在→查 DB 回填；hit-empty=空标记命中→返回空不查 DB；
 * hit-data=数据命中→直接返回。空标记为独立 {@code empty:{dataKey}} key + 短 TTL（4.4）。
 */
@Component
public class CacheAside {

    private static final Logger LOGGER = LogUtil.getLogger(CacheAside.class);

    /** TTL 简单抖动 ±10%（4.12 一版"固定 TTL + 简单抖动"），仅作用于数据 key。 */
    private static final double JITTER_RATIO = 0.1;
    private static final long MIN_TTL_SECONDS = 1;

    private final RedisAccess redis;
    private final JacksonCodec codec;
    private final SingleFlight singleFlight;
    private final CacheStats stats;
    private final Random random = new Random();

    @InjectConstructor
    public CacheAside(RedisAccess redis, JacksonCodec codec, SingleFlight singleFlight,
                      CacheStats stats) {
        this.redis = redis;
        this.codec = codec;
        this.singleFlight = singleFlight;
        this.stats = stats;
    }

    // ==================== 读路径 ====================

    /**
     * 纯三态读，不触发回填：HIT_EMPTY / HIT_DATA / MISS。
     *
     * <p>适用于调用方需要显式区分三态的读写策略（如点赞缓存分"缓存结果"与"DB 兜底"）。
     * Redis 异常视为 MISS（调用方走 DB），不抛异常。
     */
    public <T> CacheResult<T> read(String dataKey, Class<T> type) {
        try {
            boolean empty = redis.execute(j -> j.exists(CacheKeys.empty(dataKey)));
            if (empty) {
                stats.record(CacheStats.Event.HIT_EMPTY, dataKey);
                return CacheResult.hitEmpty();
            }
            String json = redis.execute(j -> j.get(dataKey));
            if (json != null) {
                T value = codec.fromJson(json, type);
                stats.record(CacheStats.Event.HIT_DATA, dataKey); // 反序列化成功后才算命中（脏 JSON 归降级）
                return CacheResult.hitData(value);
            }
            stats.record(CacheStats.Event.MISS, dataKey);
            return CacheResult.miss();
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "缓存读异常，视为 miss, key=" + dataKey, e);
            stats.record(CacheStats.Event.DEGRADE, dataKey);
            return CacheResult.miss();
        }
    }

    /**
     * Cache-Aside 便捷读（带单飞回填）：命中返回缓存值；空标记返回 null（不查 DB）；
     * miss 经单飞组件执行一次 loader 并回填（有数据→写数据 key，无数据→写空标记）。
     *
     * <p>Redis 异常 → 降级直接调 loader 返回、不写回（4.2/D4）；loader 自身异常原样上抛。
     *
     * @param dataKey    数据 key
     * @param type       值类型
     * @param loader     数据加载器（查 DB）
     * @param ttlSeconds 数据 key TTL（秒），将应用 ±10% 简单抖动
     */
    public <T> T get(String dataKey, Class<T> type, Callable<T> loader, long ttlSeconds) {
        return getInternal(dataKey, json -> codec.fromJson(json, type), loader, ttlSeconds);
    }

    /** {@link #get(String, Class, Callable, long)} 的泛型容器版本。 */
    public <T> T get(String dataKey, TypeReference<T> typeRef, Callable<T> loader, long ttlSeconds) {
        return getInternal(dataKey, json -> codec.fromJson(json, typeRef), loader, ttlSeconds);
    }

    private <T> T getInternal(String dataKey, Function<String, T> parse,
                              Callable<T> loader, long ttlSeconds) {
        try {
            boolean empty = redis.execute(j -> j.exists(CacheKeys.empty(dataKey)));
            if (empty) {
                stats.record(CacheStats.Event.HIT_EMPTY, dataKey);
                return null;
            }
            String json = redis.execute(j -> j.get(dataKey));
            if (json != null) {
                T value = parse.apply(json);
                stats.record(CacheStats.Event.HIT_DATA, dataKey); // 反序列化成功后才算命中（脏 JSON 归降级）
                return value;
            }
            stats.record(CacheStats.Event.MISS, dataKey);
            return singleFlight.get(dataKey, () -> {
                T value = invokeLoader(dataKey, loader);
                if (value != null) {
                    writeOrInvalidate(dataKey, value, ttlSeconds);
                } else {
                    markEmpty(dataKey);
                }
                return value;
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "缓存读降级走 DB, key=" + dataKey, e);
            stats.record(CacheStats.Event.DEGRADE, dataKey);
            return invokeLoader(dataKey, loader);
        }
    }

    // ==================== 写路径（写失败=DEL 降级，4.2） ====================

    /**
     * 写数据 key（带 TTL 抖动），并同步清除空标记（防"假空"窗口：
     * 若此前 loader 无数据时写过 {@code empty:} 标记，不清除会让读路径最长 60s 返回空）。
     * 失败 → 失效（DEL 数据 key + 空标记）让读自愈，**不抛出**。
     *
     * <p>语义是"删掉旧缓存让它自愈"，不是忽略写失败留着旧缓存——否则热门内容的新数据长期不可见。
     */
    public void writeOrInvalidate(String dataKey, Object value, long ttlSeconds) {
        try {
            String json = codec.toJson(value);
            long ttl = applyJitter(ttlSeconds);
            redis.executeVoid(j -> {
                j.setex(dataKey, ttl, json);
                j.del(CacheKeys.empty(dataKey));
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "缓存写失败，失效 key 让读自愈, key=" + dataKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, dataKey);
            deleteQuietly(dataKey);
        }
    }

    /**
     * 写空标记（独立 key + 短 TTL，4.4）：确认"已加载、无数据"，并清掉旧数据 key。
     * 失败仅记日志，不抛出（读路径会视同 miss 走 DB 自愈）。
     */
    public void markEmpty(String dataKey) {
        try {
            redis.executeVoid(j -> {
                j.setex(CacheKeys.empty(dataKey), CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                        CacheKeys.EMPTY_MARKER_VALUE);
                j.del(dataKey);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "空标记写入失败, key=" + dataKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, dataKey);
        }
    }

    /**
     * 业务显式失效（4.5）：逐个 DEL 数据 key 及其空标记。全程 best-effort 不抛出。
     *
     * <p>用于内容/评论解耦后的显式失效（如删除内容 → 级联删内容与评论 key）。
     */
    public void invalidate(String... dataKeys) {
        for (String dataKey : dataKeys) {
            deleteQuietly(dataKey);
        }
    }

    // ==================== 内部工具 ====================

    private void deleteQuietly(String dataKey) {
        try {
            redis.executeVoid(j -> {
                j.del(dataKey);
                j.del(CacheKeys.empty(dataKey));
            });
        } catch (CacheException e) {
            // DEL 也失败（Redis 挂）→ 读路径整体降级走 DB，仍然一致，不产生永久不可见窗口（4.2）
            LOGGER.log(Level.WARNING, "缓存失效也失败（疑似 Redis 异常），读路径将降级走 DB, key="
                    + dataKey, e);
            // 显式失效/自愈 DEL 失败也算写失败（T7 观测）；writeOrInvalidate 失败链叠加 DEL 失败罕见，可接受
            stats.record(CacheStats.Event.WRITE_FAIL, dataKey);
        }
    }

    private <T> T invokeLoader(String dataKey, Callable<T> loader) {
        stats.record(CacheStats.Event.LOAD, dataKey);
        try {
            return loader.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("缓存数据加载失败", e);
        }
    }

    /** 数据 key TTL 应用 ±10% 简单抖动（4.12），保底 1s。 */
    private long applyJitter(long baseSeconds) {
        if (baseSeconds <= 0) {
            return baseSeconds;
        }
        long delta = (long) (baseSeconds * JITTER_RATIO * random.nextDouble());
        long ttl = baseSeconds + (random.nextBoolean() ? delta : -delta);
        return Math.max(ttl, MIN_TTL_SECONDS);
    }
}