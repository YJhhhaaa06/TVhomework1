package com.itheima.cache;

import com.itheima.exception.CacheException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 有序 Set（ZSet）缓存基建组件（T7 新增，A1「缓存有序结构」落点）：与 {@link SetCache}
 * 平行，把关注域需要的"三态读（含单成员判定）/ 全量读 / **按序窗口读** / 回填（含空标记）/
 * 批量判定 / 降级单飞装载"收敛为 cache 包内一处，后续分页类需求复用同一落点。
 *
 * <p>与 {@link SetCache} 的差异只有命令层与"有序"带来的新能力：
 * Set = SISMEMBER/SMEMBERS/SADD（无序）；ZSet = ZSCORE/ZRANGE/ZADD（**按 score 升序**）。
 * 其余契约逐条同构（打点口径 / 空标记 / 滑动续期 / 降级不写回 / 回填不 DEL）。
 *
 * <p><b>score 约定</b>：本组件一律以**成员自身的数值**作为 score（见 {@link #writeZSet}），
 * 因此 ZSet 的天然遍历序 = 成员数值升序；这正是关注域"按 followedUserId 升序"的既有
 * 确定性顺序（{@code FollowCache.sortIds} 口径）。降级/未回填路径在内存切片前按同口径
 * 升序排列，保证 hit / miss / 降级三路径**同一顺序**（防热/冷读顺序波动）。
 *
 * <p>语义对齐 {@link SetCache}（红线：key 命名 / 三态语义 / 空标记 TTL / 降级语义一概不变）：
 * <ul>
 *   <li>三态顺序：先空标记（{@code empty:{zsetKey}}）→ 后数据 → miss 才单飞回填；</li>
 *   <li>统计打点：六事件 key = 数据 zsetKey；批量打点粒度保持每 (数据 key, 决策) 记一次；</li>
 *   <li>滑动续期：探针无条件入列 {@code expire(zsetKey, ttl)}（精确 TTL 无抖动），
 *       空标记 key 从不续期；</li>
 *   <li>回填：非空 ZADD + EXPIRE（不 DEL，避免丢失并发写）；空 → {@link CacheAside#markEmpty}
 *       （exists 守卫同源）；</li>
 *   <li>降级不放量：Redis 异常经单飞全量装载作答、不写回；loader 失败以异常收场。</li>
 * </ul>
 */
@Component
public class ZSetCache {

    private static final Logger LOGGER = LogUtil.getLogger(ZSetCache.class);

    private final RedisAccess redis;
    private final CacheAside cacheAside;
    private final SingleFlight singleFlight;
    private final CacheStats stats;

    @InjectConstructor
    public ZSetCache(RedisAccess redis, CacheAside cacheAside, SingleFlight singleFlight,
                     CacheStats stats) {
        this.redis = redis;
        this.cacheAside = cacheAside;
        this.singleFlight = singleFlight;
        this.stats = stats;
    }

    // ==================== 读-单成员三态（含单飞回填 + 降级） ====================

    /**
     * 查询 {@code member} 是否属于 {@code zsetKey}：
     * hit-empty（空标记）→ false；hit-data（zset 存在）→ ZSCORE 非 null；miss → 单飞回填
     * （loader 全量装载 → 写 zset 或空标记）后判成员；Redis 异常 → 降级（经单飞全量装载
     * 作答，同 key 并发只打一次 DB；仅装载不写回）。
     *
     * @return 是否成员（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isMember(String zsetKey, long member, Supplier<Collection<Long>> loader,
                            long ttlSeconds) {
        try {
            List<Object> scan = scanZSet(zsetKey, String.valueOf(member), ttlSeconds);
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, zsetKey);
                return false; // 空标记：已确认无成员
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, zsetKey);
                return scan.get(2) != null; // hit-data：ZSCORE 非 null 即成员
            }
            // miss：单飞回填（负载 = DB 全量成员 → 写 zset 或空标记）
            stats.record(CacheStats.Event.MISS, zsetKey);
            Collection<Long> members = singleFlight.get(zsetKey, () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                Collection<Long> loaded = loader.get();
                writeZSet(zsetKey, loaded, ttlSeconds);
                return loaded;
            });
            return members.contains(member);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 缓存读失败，降级 DB, key=" + zsetKey, e);
            stats.record(CacheStats.Event.DEGRADE, zsetKey);
            // 降级不放量：降级经单飞全量装载作答（与 miss 回填同 key 同 loader）——不写回
            return loadViaSingleFlight(zsetKey, loader).contains(member);
        }
    }

    // ==================== 读-全量（三态 + 单飞回填 + 降级） ====================

    /**
     * 读取 zset 全量成员：hit-empty → 空列表（确认真无）；zset 存在 → {@code ZRANGE 0 -1}
     * （**按 score 升序**，即成员数值升序）；miss → 单飞回填（DB 全量 → 非空写 zset / 空写
     * 空标记）后返回 loader 顺序；Redis 异常 → DB 降级。
     *
     * <p>miss / 降级返回 loader 原始顺序（与 {@link SetCache#getMembers} 契约一致，调用方
     * 需确定性顺序时自行包装）；hit-data 已是升序。
     */
    public List<Long> getMembers(String zsetKey, Supplier<Collection<Long>> loader,
                                 long ttlSeconds) {
        try {
            List<Boolean> probe = probeZSet(zsetKey, ttlSeconds);
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, zsetKey);
                return Collections.emptyList(); // 空标记：已确认无数据
            }
            if (Boolean.TRUE.equals(probe.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, zsetKey);
                return toLongList(redis.execute(j -> j.zrange(zsetKey, 0, -1)));
            }
            // miss：单飞回填；返回 loader 顺序（与调用方约定，不排序）
            stats.record(CacheStats.Event.MISS, zsetKey);
            return new ArrayList<>(singleFlight.get(zsetKey, () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                Collection<Long> loaded = loader.get();
                writeZSet(zsetKey, loaded, ttlSeconds);
                return loaded;
            }));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 全量读失败，降级 DB, key=" + zsetKey, e);
            stats.record(CacheStats.Event.DEGRADE, zsetKey);
            return new ArrayList<>(loadViaSingleFlight(zsetKey, loader));
        }
    }

    // ==================== 读-按序窗口（分页载体，T7 A1 新增） ====================

    /**
     * 按序窗口读：取升序序列中 {@code [offset, offset+count)} 区间 + 总成员数（一趟 pipeline：
     * {@code ZRANGE start stop} + {@code ZCARD}）。
     *
     * <p>三态：hit-empty → 空窗口（total=0）；hit-data → 上述 pipeline；miss → 单飞回填后
     * **重读窗口**（以 Redis 内容为准，与并发写入合并后的成员集一致）；Redis 异常 → 降级：
     * 单飞全量装载后在内存按 score 口径（成员数值升序）切片作答、不写回。
     *
     * <p>三路径顺序一致：ZRANGE 的 score 升序 = 成员数值升序 = 降级内存切片的排序口径。
     *
     * @param offset     起始下标（0 基，越界 → 空列表）
     * @param count      期望条数（≤0 → 空列表）
     * @param ttlSeconds 回填 TTL（命中顺带续期同 {@link SetCache}）
     */
    public Window getWindow(String zsetKey, long offset, int count,
                            Supplier<Collection<Long>> loader, long ttlSeconds) {
        if (offset < 0 || count <= 0) {
            return new Window(Collections.emptyList(), 0);
        }
        try {
            List<Boolean> probe = probeZSet(zsetKey, ttlSeconds);
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, zsetKey);
                return new Window(Collections.emptyList(), 0);
            }
            if (Boolean.TRUE.equals(probe.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, zsetKey);
                return readWindow(zsetKey, offset, count);
            }
            // miss：单飞回填（loader 全量 → 写 zset / 空标记）后重读窗口
            stats.record(CacheStats.Event.MISS, zsetKey);
            Collection<Long> loaded = singleFlight.get(zsetKey, () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                Collection<Long> l = loader.get();
                writeZSet(zsetKey, l, ttlSeconds);
                return l;
            });
            if (loaded.isEmpty()) {
                return new Window(Collections.emptyList(), 0);
            }
            return readWindow(zsetKey, offset, count);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 窗口读失败，降级 DB, key=" + zsetKey, e);
            stats.record(CacheStats.Event.DEGRADE, zsetKey);
            List<Long> all = new ArrayList<>(loadViaSingleFlight(zsetKey, loader));
            Collections.sort(all); // score 口径 = 成员数值，与 hit-data 的 ZRANGE 序一致
            return new Window(slice(all, offset, count), all.size());
        }
    }

    // ==================== 批量-单 zset 多成员（Follow 形态：user:following 对多目标） ====================

    /**
     * 批量查询同一 zset 内多个成员的存在性（一趟 pipeline 探 empty/exists + N×ZSCORE）。
     *
     * <p>空标记 → 全部 false；zset 存在 → 逐个 ZSCORE 判定；miss → dbAnswer 批量作答
     * （DB 为最终真理，失败上抛）+ 单飞全量回填（best-effort，失败仅记日志——DB 答案已返回）；
     * Redis 异常 → 降级：单飞全量装载作答、不写回。
     *
     * @param dbAnswer miss 答案函数（不得返回 null；返回空 Set 表示无命中成员）
     */
    public Map<Long, Boolean> batchIsMember(String zsetKey, List<Long> members,
                                            Function<List<Long>, Set<Long>> dbAnswer,
                                            Supplier<Collection<Long>> fullLoader,
                                            long ttlSeconds) {
        if (members == null || members.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
        boolean degraded = false;
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
                Response<Boolean> exists = p.exists(zsetKey);
                List<Response<Double>> memberResps = new ArrayList<>(members.size());
                for (Long id : members) {
                    memberResps.add(p.zscore(zsetKey, String.valueOf(id)));
                }
                // 滑动续期：命中 zset 顺带续期（无条件入列，key 不存在返回 0 无效果；空标记不续）
                p.expire(zsetKey, ttlSeconds);
                p.sync();
                if (Boolean.TRUE.equals(empty.get())) {
                    stats.record(CacheStats.Event.HIT_EMPTY, zsetKey);
                    for (Long id : members) {
                        result.put(id, false);
                    }
                    return;
                }
                if (Boolean.TRUE.equals(exists.get())) {
                    stats.record(CacheStats.Event.HIT_DATA, zsetKey);
                    for (int i = 0; i < members.size(); i++) {
                        result.put(members.get(i), memberResps.get(i).get() != null);
                    }
                    return;
                }
                stats.record(CacheStats.Event.MISS, zsetKey);
                missed.addAll(members);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 批量读失败，全部走 DB, key=" + zsetKey, e);
            stats.record(CacheStats.Event.DEGRADE, zsetKey);
            degraded = true;
            missed.addAll(members);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                Collection<Long> all = loadViaSingleFlight(zsetKey, fullLoader);
                for (Long id : missed) {
                    result.put(id, all.contains(id));
                }
            } else {
                Set<Long> answered = dbAnswer.apply(missed);
                for (Long id : missed) {
                    result.put(id, answered.contains(id));
                }
                // 同一 key 只回填一次（singleFlight 内 load 全量 + 写 zset）；best-effort：失败仅记日志，DB 答案照常返回
                try {
                    singleFlight.get(zsetKey, () -> {
                        stats.record(CacheStats.Event.LOAD, zsetKey);
                        Collection<Long> loaded = fullLoader.get();
                        writeZSet(zsetKey, loaded, ttlSeconds);
                        return null;
                    });
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "ZSet 批量回填失败，仅影响缓存, key=" + zsetKey, e);
                }
            }
        }
        return result;
    }

    // ==================== 回填（单飞 loader 内调用；空集 → 空标记） ====================

    /**
     * ZSet 成员回填：非空 → {@code ZADD score=成员数值}+ EXPIRE（不 DEL，避免丢失并发写）；
     * 空 → {@link CacheAside#markEmpty}（内含"数据 key 不存在才写"的存在守卫）。
     * Redis 异常 → 记 WRITE_FAIL，不抛出（读路径视同 miss 走 DB 自愈）。
     */
    public void writeZSet(String zsetKey, Collection<Long> members, long ttlSeconds) {
        if (members == null || members.isEmpty()) {
            cacheAside.markEmpty(zsetKey);
            return;
        }
        try {
            redis.executeVoid(j -> {
                Map<String, Double> scored = new LinkedHashMap<>();
                for (Long m : members) {
                    scored.put(String.valueOf(m), m.doubleValue()); // score = 成员数值（见类注释）
                }
                j.zadd(zsetKey, scored);
                j.expire(zsetKey, ttlSeconds);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 回填失败，读自愈, key=" + zsetKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, zsetKey);
        }
    }

    // ==================== 降级装载（唯一入口，与 SetCache 同口径） ====================

    /**
     * 降级装载：经单飞执行 loader 并返回结果——同 key 并发只打一次 DB；仅装载不写回；
     * LOAD 由 leader 记一次（与 miss 单飞口径一致）。
     */
    public <T> T loadViaSingleFlight(String zsetKey, Supplier<T> loader) {
        return singleFlight.get(zsetKey, () -> {
            stats.record(CacheStats.Event.LOAD, zsetKey);
            return loader.get();
        });
    }

    // ==================== 内部：三态扫描 / 探测 / 转换 ====================

    /**
     * 单成员三态扫描：一趟 pipeline 返回 [空标记, key 存在, ZSCORE]（ZSCORE 可能为 null）。
     * 滑动续期：命中顺带续期（无条件入列，key 不存在返回 0 无效果；空标记不续）。
     */
    private List<Object> scanZSet(String zsetKey, String member, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
            Response<Boolean> exists = p.exists(zsetKey);
            Response<Double> score = p.zscore(zsetKey, member);
            p.expire(zsetKey, ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), score.get());
        });
    }

    /** 探测：一趟 pipeline 返回 [空标记, key 存在]（滑动续期同 {@link #scanZSet}）。 */
    private List<Boolean> probeZSet(String zsetKey, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
            Response<Boolean> exists = p.exists(zsetKey);
            p.expire(zsetKey, ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get());
        });
    }

    /** 命中路径的窗口读：一趟 pipeline 取 [offset, offset+count) 升序成员 + ZCARD 总数。 */
    private Window readWindow(String zsetKey, long offset, int count) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<List<String>> range = p.zrange(zsetKey, offset, offset + count - 1L);
            Response<Long> card = p.zcard(zsetKey);
            p.sync();
            List<Long> ids = toLongList(range.get());
            Long total = card.get();
            return new Window(ids, total == null ? ids.size() : total);
        });
    }

    /** ZRANGE 结果（String，已按 score 升序）转 Long 列表。 */
    private List<Long> toLongList(List<String> members) {
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            result.add(Long.valueOf(m));
        }
        return result;
    }

    /** 升序列表的窗口切片（越界返回空列表）。 */
    private List<Long> slice(List<Long> ordered, long offset, int count) {
        if (offset >= ordered.size()) {
            return Collections.emptyList();
        }
        int from = (int) offset;
        int to = (int) Math.min(ordered.size(), offset + count);
        return new ArrayList<>(ordered.subList(from, to));
    }

    /**
     * 窗口读结果：该页成员（升序）+ 总成员数。
     *
     * <p>total 与 ids **同源同一 key**（ZCARD / 装载结果 size），调用方可据此拼
     * {@code PageResult}（totalPages 由页码与页大小推导），避免与独立计数 key 的瞬时不一致。
     */
    public static final class Window {
        private final List<Long> ids;
        private final long total;

        public Window(List<Long> ids, long total) {
            this.ids = ids;
            this.total = total;
        }

        public List<Long> getIds() {
            return ids;
        }

        public long getTotal() {
            return total;
        }
    }
}
