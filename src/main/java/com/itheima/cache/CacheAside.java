package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>读路径加固（T8）：单 key 读（{@link #read} / {@link #get}）与批量读
 * （{@link #getBatch}）均 pipeline 化，EXISTS 空标记 + GET 数据 key 一趟往返（治 H12）；
 * 批量三态语义与单 key 完全一致。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读与 miss 回填共用同一 {@link SingleFlight}——
 * 同 key 并发读（单 key / 批量 / 脏 JSON）只打一次 DB；降级仅装载、不写回（D4），
 * loader 失败以异常收场且条目移除（失败不以数据形式共享，下一请求全新重试）。
 *
 * <p>负缓存治理（三期 T3）：loader 契约——返回 null 仅表示"确认无数据"（允许写空标记）；
 * 抛 {@link DatabaseException} 表示"加载失败"（DB 瞬时故障/意外异常），装载处捕获后转 null，
 * **不写空标记、不 DEL 既有数据 key**（读路径不把瞬时故障固化成假空）。该契约仅对
 * {@link DatabaseException} 生效；like/follow 域 loader 抛 {@code ServerException}（500 语义）
 * 不受影响。对外行为零变化（内容 404 / 评论空 / 批量跳过），只治理缓存写层。
 *
 * <p>滑动续期（T9 O-8）：**读命中顺带续期**——{@link #get}（{@link #getInternal}）与
 * {@link #getBatch} 在命中数据 key 时同 pipeline 追加 {@code EXPIRE}（续期值=原 TTL ±10% 抖动），
 * 热点 key 常驻由续期自然达成、不设永不过期 key；**空标记（{@code empty:}）一律不续期**
 * （防"假空"窗口延长，NEEDS 4.14）；{@link #read} 为纯三态读不续期。
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
            List<Object> probe = probe(dataKey);
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, dataKey);
                return CacheResult.hitEmpty();
            }
            String json = (String) probe.get(1);
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
     * <p>Redis 异常 → 降级经单飞调 loader 返回、不写回（4.2/D4；三期 T2 降级接入单飞，
     * 同 key 并发只打一次 DB）；loader 自身异常原样上抛。
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
            List<Object> probe = probeRenew(dataKey, ttlSeconds);
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, dataKey);
                return null;
            }
            String json = (String) probe.get(1);
            if (json != null) {
                T value = parse.apply(json);
                stats.record(CacheStats.Event.HIT_DATA, dataKey); // 反序列化成功后才算命中（脏 JSON 归降级）
                return value;
            }
            stats.record(CacheStats.Event.MISS, dataKey);
            return singleFlight.get(dataKey, () -> {
                T value;
                try {
                    value = invokeLoader(dataKey, loader);
                } catch (DatabaseException e) {
                    // 三期 T3：加载失败 ≠ 确认无数据——不写空标记、不 DEL（读路径不固化瞬时故障）
                    LOGGER.log(Level.WARNING, "缓存加载失败（不写空标记、不 DEL 数据 key）, key=" + dataKey, e);
                    return null;
                }
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
            // 三期 T2 降级不放量：降级亦经单飞（与 miss 回填同 key 空间）——同 key 并发读只打一次 DB；
            // 仅装载不写回（D4）；LOAD 由 leader 记一次（与 miss 单飞口径一致）
            return singleFlight.get(dataKey, () -> loadDegraded(dataKey, loader));
        }
    }

    // ==================== 批量读（T8 二期读路径加固） ====================

    /**
     * 批量 Cache-Aside 读：一趟 pipeline 拉全部数据 key（EXISTS 空标记 + GET），
     * 三态语义与单 key {@link #get(String, Class, Callable, long)} 完全一致
     * （NEEDS 4.3 顺序：先空标记后数据 key），miss 项逐个单飞回填。
     *
     * <p>返回 {@code dataKey → value} 全量 Map（null 值合法 = hit-empty / 加载为空），
     * 调用方按键取值；调用方保证 dataKeys 无重复。
     *
     * <p>降级语义与单 key 一致：整批 Redis 异常或单个 key JSON 解析失败 → 该 key
     * DEGRADE + 直接 loader（不写回；三期 T2 起降级亦经单飞，同 key 并发只打一次 DB）；
     * miss → 单飞回填（LOAD + 写回/空标记）。
     *
     * @param loader key → 数据加载器（查 DB）
     */
    public <T> Map<String, T> getBatch(List<String> dataKeys, Class<T> type,
                                       Function<String, T> loader, long ttlSeconds) {
        if (dataKeys == null || dataKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, T> result = new HashMap<>();
        List<String> missed = new ArrayList<>();
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                Map<String, Response<Boolean>> empties = new HashMap<>();
                Map<String, Response<String>> jsons = new HashMap<>();
                for (String key : dataKeys) {
                    empties.put(key, p.exists(CacheKeys.empty(key)));
                    jsons.put(key, p.get(key));
                    // T9 滑动续期：命中数据 key 顺带续期（值=原 TTL 抖动）。无条件入列——
                    // hit-empty/miss 时 data key 不存在，EXPIRE 返回 0 无效果；空标记 key 从不续期
                    p.expire(key, applyJitter(ttlSeconds));
                }
                p.sync();
                for (String key : dataKeys) {
                    if (Boolean.TRUE.equals(empties.get(key).get())) {
                        stats.record(CacheStats.Event.HIT_EMPTY, key);
                        result.put(key, null);
                    } else {
                        String json = jsons.get(key).get();
                        if (json != null) {
                            try {
                                T value = codec.fromJson(json, type);
                                stats.record(CacheStats.Event.HIT_DATA, key); // 反序列化成功后才算命中
                                result.put(key, value);
                            } catch (CacheException e) {
                                // 单 key 解析失败语义（对齐 getInternal catch 分支）：该 key 降级直接 loader，不拖垮整批；
                                // 三期 T2：降级经单飞去重（同 key 并发只打一次 DB），仅装载不写回
                                LOGGER.log(Level.WARNING, "批量缓存反序列化失败，该 key 降级, key=" + key, e);
                                stats.record(CacheStats.Event.DEGRADE, key);
                                result.put(key, singleFlight.get(key,
                                        () -> loadDegraded(key, () -> loader.apply(key))));
                            }
                        } else {
                            stats.record(CacheStats.Event.MISS, key);
                            missed.add(key);
                        }
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量缓存读降级走 DB, keys=" + dataKeys.size(), e);
            for (String key : dataKeys) {
                stats.record(CacheStats.Event.DEGRADE, key);
                // 三期 T2：降级逐 key 经单飞——同 key 并发批量读只打一次 DB（仅装载，不写回）；
                // 三期 T3：DB 加载失败转 null（逐 key 优雅降级，不拖垮整批）
                result.put(key, singleFlight.get(key,
                        () -> loadDegraded(key, () -> loader.apply(key))));
            }
        }
        if (!missed.isEmpty()) {
            for (String key : missed) {
                result.put(key, singleFlight.get(key, () -> {
                    T value;
                    try {
                        value = invokeLoader(key, () -> loader.apply(key));
                    } catch (DatabaseException e) {
                        // 三期 T3：加载失败 ≠ 确认无数据——不写空标记、不 DEL（读路径不固化瞬时故障）
                        LOGGER.log(Level.WARNING, "批量缓存加载失败（不写空标记、不 DEL 数据 key）, key=" + key, e);
                        return null;
                    }
                    if (value != null) {
                        writeOrInvalidate(key, value, ttlSeconds);
                    } else {
                        markEmpty(key);
                    }
                    return value;
                }));
            }
        }
        return result;
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
     * 写空标记（独立 key + 短 TTL，4.4）：确认"已加载、无数据"。
     *
     * <p>三期 T4/N3 存在守卫：数据 key 已存在（并发回填/业务写刚写入真数据）时跳过，
     * 防止空标记 + DEL 把真数据固化成 60s 假空（对齐 FollowCache.writeSet 260913 先例）；
     * 原 del(dataKey) 一并移除——守卫内为死代码，且竞态下会删真数据，是 N3 危害的一部分。
     *
     * <p>失败仅记日志，不抛出（读路径会视同 miss 走 DB 自愈）。守卫检查（exists）失败
     * 同样归入此分支：宁可少写空标记（多一次 DB 查询），绝不误写（假空）。
     *
     * <p>残余竞态（已接受，见任务清单 T4 回写）：exists 检查 → setex 之间的毫秒间隙内
     * 并发写入时，空标记可能覆盖其上——数据 key 未被删，空标记 60s 过期或下次业务写
     * {@link #writeOrInvalidate} 清空标记即自愈，无真数据丢失。
     */
    public void markEmpty(String dataKey) {
        try {
            redis.executeVoid(j -> {
                if (!j.exists(dataKey)) {
                    j.setex(CacheKeys.empty(dataKey), CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                            CacheKeys.EMPTY_MARKER_VALUE);
                }
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

    /**
     * 一趟 pipeline 探测单 key 的 [空标记, 数据 JSON]（T8：读路径 EXISTS + GET 合一趟往返，治 H12）。
     * 供 {@link #read(String, Class)} 复用（纯三态读，不续期）。
     */
    private List<Object> probe(String dataKey) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(dataKey));
            Response<String> json = p.get(dataKey);
            p.sync();
            return java.util.Arrays.asList(empty.get(), json.get());
        });
    }

    /**
     * T9 滑动续期版探测：与 {@link #probe(String)} 同构，命中数据 key 时顺带续期
     * （值=原 TTL ±10% 抖动），供 {@link #getInternal} 使用。
     *
     * <p>EXPIRE 对 data key **无条件入列**：hit-data 时数据 key 存在 → 续期生效；
     * hit-empty / miss 时数据 key 不存在 → EXPIRE 返回 0 无效果，且**空标记 key
     * （{@code empty:}）从不被续期**（NEEDS 4.14：防"假空"窗口延长）。
     * EXPIRE 的 Response 无需读取；Redis 异常由调用方既有 catch 降级兜底，
     * EXPIRE 失败不影响读返回。空标记的短 TTL 由 markEmpty/writeOrInvalidate 维护，与续期无关。
     */
    private List<Object> probeRenew(String dataKey, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(dataKey));
            Response<String> json = p.get(dataKey);
            p.expire(dataKey, applyJitter(ttlSeconds));
            p.sync();
            return java.util.Arrays.asList(empty.get(), json.get());
        });
    }

    /** 降级装载：记 LOAD（invokeLoader 口径）；DatabaseException（DB 加载失败）→ 记日志转 null（不写回，D4）。 */
    private <T> T loadDegraded(String dataKey, Callable<T> loader) {
        try {
            return invokeLoader(dataKey, loader);
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "降级装载失败（不写回）, key=" + dataKey, e);
            return null;
        }
    }

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