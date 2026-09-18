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
 *
 * <p>装载合并（第五期 T2 N1）：{@link #getBatch(List, Class, Function, long)} 的 miss 子集与
 * 整批降级子集原为**逐 key** 调 loader（每 key 一次 DB 往返），现可经
 * {@link #getBatch(List, Class, Function, BatchLoader, long)} 传入 {@link BatchLoader}
 * **一次装载全部待装 key**（冷数据页 DB 趟数从 2N 收敛为常量）；三态/续期/空标记/降级/单飞/打点
 * 语义零变化，逐 key 单飞去重仍生效（见 {@link LoadMemo}）。
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
        return getBatch(dataKeys, type, loader, null, ttlSeconds);
    }

    /**
     * 批量 Cache-Aside 读（第五期 T2 装载合并，治 N1）：三态/续期/空标记/降级/单飞/打点语义与
     * {@link #getBatch(List, Class, Function, long)} **完全一致**，仅装载粒度不同——
     * miss 子集与整批降级子集的 DB 装载由 {@code batchLoader} **一次**完成（常量趟数），
     * 不再逐 key 各查一次 DB。
     *
     * <p>逐 key {@code loader} 仍保留：仅用于**单 key 脏 JSON 降级**路径（该 key 单独降级、
     * 不拖垮整批，且只需为这一个 key 取数）；{@code batchLoader == null} 时全部装载走逐 key
     * （等价于历史语义：单 key 加载失败只影响该 key）。
     *
     * <p>逐 key 单飞去重不变（见 {@link LoadMemo}）：同一 key 的并发读仍只触发一次装载。
     * LOAD 打点口径不变（每个待装载 key 各记一次，"DB 装载尝试次数"）；
     * 批量装载抛 {@link DatabaseException}（加载失败）→ 该批全部 key 视同"加载失败"：
     * miss 路径**不写空标记、不 DEL**，降级路径**不写回**，逐 key 返回 null（三期 T3 负缓存契约）。
     *
     * @param loader      key → 数据加载器（单 key 脏 JSON 降级路径）
     * @param batchLoader 批量加载器（miss / 整批降级路径；null = 退化为逐 key 装载）
     */
    public <T> Map<String, T> getBatch(List<String> dataKeys, Class<T> type,
                                       Function<String, T> loader, BatchLoader<T> batchLoader,
                                       long ttlSeconds) {
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
            // 整批降级（T2：一趟批量装载覆盖全部 key），逐 key 单飞去重与"仅装载不写回"口径不变
            LoadMemo<T> memo = new LoadMemo<>(dataKeys, loader, batchLoader, stats);
            for (String key : dataKeys) {
                stats.record(CacheStats.Event.DEGRADE, key);
                // 三期 T2：降级逐 key 经单飞——同 key 并发批量读只打一次 DB（仅装载，不写回）；
                // 三期 T3：DB 加载失败转 null（逐 key 优雅降级，不拖垮整批）
                result.put(key, singleFlight.get(key, () -> {
                    LoadOutcome<T> outcome = memo.resolve(key);
                    return outcome.isFailed() ? null : outcome.value();
                }));
            }
        }
        if (!missed.isEmpty()) {
            // miss 子集共享一次批量装载（T2：本批只装载本批 miss 的 key，不重复查已命中项）
            LoadMemo<T> memo = new LoadMemo<>(missed, loader, batchLoader, stats);
            for (String key : missed) {
                result.put(key, singleFlight.get(key, () -> {
                    LoadOutcome<T> outcome = memo.resolve(key);
                    if (outcome.isFailed()) {
                        // 三期 T3：加载失败 ≠ 确认无数据——不写空标记、不 DEL（读路径不固化瞬时故障）
                        return null;
                    }
                    if (outcome.value() != null) {
                        writeOrInvalidate(key, outcome.value(), ttlSeconds);
                    } else {
                        markEmpty(key);
                    }
                    return outcome.value();
                }));
            }
        }
        return result;
    }

    /**
     * 批量装载器契约（第五期 T2 装载合并）：一次装载多个数据 key 的值，替代逐 key 调 loader。
     *
     * <p>实现约定（对齐三期 T3 负缓存契约）：
     * <ul>
     *   <li>**必须为每个请求 key 给出条目**——无数据用 {@code null}（=确认无数据，允许写空标记）；
     *       漏 key 视为契约违规，按"加载失败"处理（不写假空标记，下次读重新装载）；</li>
     *   <li>整批加载失败（如 SQLException 经事务模板包成 {@link DatabaseException}）直接抛出，
     *       由 {@link #getBatch(List, Class, Function, BatchLoader, long)} 按"加载失败"处理
     *       （不写空标记、不写回）；</li>
     *   <li>抛非 {@code DatabaseException} 的运行时异常 → 沿单飞原样上抛（不吞，语义同逐 key loader）。</li>
     * </ul>
     */
    @FunctionalInterface
    public interface BatchLoader<T> {
        Map<String, T> load(List<String> dataKeys);
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
     * 批量写数据 key（三期 T5：启动全量重建专属，一趟 pipeline 写全部 key 并同步清空标记）。
     *
     * <p>语义与 {@link #writeOrInvalidate} 逐 key 完全一致：per-key TTL 抖动、SETEX + DEL 空标记；
     * 失败 → 逐 key 失效（DEL 数据 key + 空标记）让读自愈、记 WRITE_FAIL，**不抛出**。
     * 仅 {@code ContentCache.init()} 全量重建使用；hot path 单写仍走 {@link #writeOrInvalidate}。
     * 与单写不同：批内单命令的 server 级错误依赖 {@code Pipeline.sync()} 抛异常统一兜底
     * （与 probe/getBatch 的 pipeline 可靠性模型一致；断连/超时主失败形态下 sync 必抛）。
     */
    public void writeBatch(Map<String, Object> dataKeyToValue, long ttlSeconds) {
        if (dataKeyToValue == null || dataKeyToValue.isEmpty()) {
            return;
        }
        try {
            Map<String, String> jsonByKey = new HashMap<>(dataKeyToValue.size());
            for (Map.Entry<String, Object> e : dataKeyToValue.entrySet()) {
                jsonByKey.put(e.getKey(), codec.toJson(e.getValue()));
            }
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                for (Map.Entry<String, String> e : jsonByKey.entrySet()) {
                    p.setex(e.getKey(), applyJitter(ttlSeconds), e.getValue());
                    p.del(CacheKeys.empty(e.getKey()));
                }
                p.sync();
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "缓存批量写失败，逐 key 失效让读自愈, keys=" + dataKeyToValue.size(), e);
            for (String dataKey : dataKeyToValue.keySet()) {
                stats.record(CacheStats.Event.WRITE_FAIL, dataKey);
                deleteQuietly(dataKey);
            }
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

    /**
     * 装载备忘（第五期 T2 装载合并，治 N1）：把**一次批量读内的全部待装载 key** 收敛为
     * **一次**批量装载（DB 趟数从 2N 收敛为常量），结果由本次批量读内的各 key 共享。
     *
     * <p>单飞去重不受影响：各 key 仍各自经 {@code SingleFlight.get(key, ...)} 调
     * {@link #resolve(String)}——首个成为 leader 的 key 触发批量装载，其余 key（含并发等待者）
     * 直接命中备忘，同 key 并发读仍只触发一次装载。
     *
     * <p>{@code batchLoader == null} 时退化为**逐 key 装载**（等价于历史语义：单 key 加载失败
     * 只影响该 key，不拖垮整批）。
     *
     * <p>LOAD 打点口径与逐 key 装载一致：装载尝试发生时，本批覆盖的每个 key 各记一次
     * （语义= "DB 装载尝试次数"，失败也计入）。
     */
    private static final class LoadMemo<T> {

        private final List<String> keys;
        private final Function<String, T> loader;
        private final BatchLoader<T> batchLoader;
        private final CacheStats stats;
        private final Object lock = new Object();

        /** 批量装载结果（含 null 值 = 确认无数据）；null = 尚未装载。 */
        private Map<String, T> loaded;

        /** 批量装载已执行（无论成败）；保证"一次批量读内只装载一趟"。 */
        private boolean done;

        /** 批量装载失败（DatabaseException）——失败态对该批全部 key 生效。 */
        private boolean failed;

        LoadMemo(List<String> keys, Function<String, T> loader, BatchLoader<T> batchLoader, CacheStats stats) {
            this.keys = keys;
            this.loader = loader;
            this.batchLoader = batchLoader;
            this.stats = stats;
        }

        /** 装载单 key，返回 LOADED / EMPTY / FAILED 三态之一。 */
        LoadOutcome<T> resolve(String key) {
            if (batchLoader == null) {
                // 逐 key 语义（历史路径）：LOAD 按 key 记，失败只影响该 key
                stats.record(CacheStats.Event.LOAD, key);
                try {
                    T value = loader.apply(key);
                    return value == null ? LoadOutcome.empty() : LoadOutcome.of(value);
                } catch (DatabaseException e) {
                    LOGGER.log(Level.WARNING, "批量缓存加载失败（不写空标记、不 DEL 数据 key）, key=" + key, e);
                    return LoadOutcome.fail();
                }
            }
            loadOnceIfNeeded();
            Map<String, T> snapshot = loaded;
            if (failed || snapshot == null) {
                return LoadOutcome.fail();
            }
            if (!snapshot.containsKey(key)) {
                // 契约违规（批量 loader 漏 key）：按"加载失败"处理——宁可多一次 DB 查询，绝不写假空
                // （对齐 markEmpty 守卫口径"宁可少写空标记，绝不误写"，防漏 key 静默固化成 60s 假空）
                LOGGER.log(Level.WARNING, "批量装载未覆盖该 key（按加载失败处理，不写空标记）, key=" + key);
                return LoadOutcome.fail();
            }
            T value = snapshot.get(key);
            return value == null ? LoadOutcome.empty() : LoadOutcome.of(value);
        }

        /** 批量装载一趟（幂等；LOAD 先记后装，失败亦计入）。 */
        private void loadOnceIfNeeded() {
            synchronized (lock) {
                if (done) {
                    return;
                }
                done = true;
                for (String key : keys) {
                    stats.record(CacheStats.Event.LOAD, key);
                }
                try {
                    Map<String, T> map = batchLoader.load(keys);
                    loaded = (map == null) ? Collections.emptyMap() : map;
                } catch (DatabaseException e) {
                    failed = true;
                    LOGGER.log(Level.WARNING, "批量缓存加载失败（不写空标记、不写回）, keys=" + keys.size(), e);
                }
            }
        }
    }

    /** 装载结果三态（T2）：LOADED（确认有数据）/ EMPTY（确认无数据，可写空标记）/ FAILED（加载失败）。 */
    private static final class LoadOutcome<T> {

        private static final LoadOutcome<?> EMPTY = new LoadOutcome<>(null, false);
        private static final LoadOutcome<?> FAILED = new LoadOutcome<>(null, true);

        private final T value;
        private final boolean failed;

        private LoadOutcome(T value, boolean failed) {
            this.value = value;
            this.failed = failed;
        }

        static <T> LoadOutcome<T> of(T value) {
            return new LoadOutcome<>(value, false);
        }

        @SuppressWarnings("unchecked")
        static <T> LoadOutcome<T> empty() {
            return (LoadOutcome<T>) EMPTY;
        }

        @SuppressWarnings("unchecked")
        static <T> LoadOutcome<T> fail() {
            return (LoadOutcome<T>) FAILED;
        }

        T value() {
            return value;
        }

        boolean isFailed() {
            return failed;
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