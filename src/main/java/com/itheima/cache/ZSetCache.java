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
 * 有序 Set（ZSet）缓存基建组件（T7 新增「缓存有序结构」落点）：与 {@link SetCache}
 * 平行，把关注域需要的"三态读（含单成员判定）/ 全量读 / **按序窗口读** / 回填（含空标记）/
 * 批量判定 / 降级单飞装载"收敛为 cache 包内一处。
 *
 * <p>与 {@link SetCache} 的差异只有命令层与"有序"带来的新能力：
 * Set = SISMEMBER/SMEMBERS/SADD（无序）；ZSet = ZSCORE/ZRANGE/ZADD（**按 score 升序**）。
 * 其余契约逐条同构（打点口径 / 空标记 / 滑动续期 / 降级不写回 / 回填不 DEL）。
 *
 * <p><b>score 约定</b>：本组件一律以**成员自身的数值**作为 score（见 {@link #writeZSet}），
 * 因此 ZSet 的天然遍历序 = 成员数值升序；这正是关注域"按 followedUserId 升序"的既有
 * 确定性顺序（{@code FollowCache.sortIds} 口径）。
 *
 * <p><b>T11-C 装载侧解耦（治 U-18）：前缀窗口装载</b>。改造前 miss / 降级都是
 * **全量**（DB 全量 loader + 全量 ZADD / 全量装载 + 内存切片），冷 key 的第 1 页与第 N 页
 * 装载量相同、都 = O(列表总量)。T11-C 起：
 * <ul>
 *   <li>集合不变量从"数据 key 存在 ⇒ 完整"放宽为 <b>"无 {@code partial:} 标记 ⇒ 完整"</b>
 *       （{@link CacheKeys#partial(String)}）：成员可能是 DB 按序的**前 W 个**（W = ZCARD）；</li>
 *   <li>窗口读只装载 {@code [0, offset+count)}（miss）或补齐 {@code [W, offset+count)}
 *       （部分态越界），装载量与**页位置**相关而与列表总量弱相关；</li>
 *   <li>部分态下判定（{@link #isMember}/{@link #batchIsMember}）的**未命中回落 DB**
 *       ——前缀里查不到不等于"不是成员"；</li>
 *   <li>全量读 {@link #getMembers} 遇部分态**必须补齐**（调用方依赖"返回全部"，静默漏成员
 *       是数据正确性问题）；</li>
 *   <li>降级（Redis 异常）从"全量装载 + 内存切片"改为 **DB 窗口直查、不装载不写回**
 *       （三期 T2 口径保持）；</li>
 *   <li>完全装载后自动清除标记、退化为完整 ZSet（等价 T7 现状）。</li>
 * </ul>
 *
 * <p>语义对齐 {@link SetCache}（红线：key 命名 / 三态语义 / 空标记 TTL / 降级语义一概不变）：
 * <ul>
 *   <li>三态顺序：先空标记（{@code empty:{zsetKey}}）→ 后数据 → miss 才单飞回填；</li>
 *   <li>统计打点：六事件 key = 数据 zsetKey；批量打点粒度保持每 (数据 key, 决策) 记一次；</li>
 *   <li>滑动续期：探针无条件入列 {@code expire(zsetKey, ttl)}（精确 TTL 无抖动），
 *       空标记 key 从不续期；<b>{@code partial:} 标记与数据 key 同步续期</b>；</li>
 *   <li>回填：非空 ZADD + EXPIRE（不 DEL，避免丢失并发写）；空 → {@link CacheAside#markEmpty}
 *       （exists 守卫同源）；</li>
 *   <li>降级不放量：Redis 异常经 DB 作答、不写回；loader 失败以异常收场。</li>
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

    /**
     * 窗口装载器（T11-C）：按升序取 DB 中 {@code [offset, offset+count)} 的成员；
     * 返回**不足 count 行即表示 DB 已到底**（调用方据此判定"完整"，清 partial 标记）。
     */
    @FunctionalInterface
    public interface WindowLoader {
        List<Long> load(long offset, int count);
    }

    // ==================== 读-单成员三态（含单飞回填 + 降级） ====================

    /**
     * 查询 {@code member} 是否属于 {@code zsetKey}：
     * hit-empty（空标记）→ false；hit-data（zset 存在）→ ZSCORE 非 null 即成员；
     * miss → 单飞回填（loader 全量装载 → 写 zset 或空标记）后判成员；Redis 异常 → 降级
     * （经单飞全量装载作答，同 key 并发只打一次 DB；仅装载不写回）。
     *
     * <p><b>部分态（T11-C）</b>：{@code partial} 标记存在时集合只是前缀，
     * ZSCORE 未命中**不可信**——必须回落 {@code dbAnswer}（DB 单行判定）才能给出"否"。
     * 标记不存在时维持原语义（未命中即可信"否"，零额外 DB 查询）。
     *
     * @return 是否成员（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isMember(String zsetKey, long member, Supplier<Collection<Long>> loader,
                            Supplier<Boolean> dbAnswer, long ttlSeconds) {
        try {
            List<Object> scan = scanZSet(zsetKey, String.valueOf(member), ttlSeconds);
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, zsetKey);
                return false; // 空标记：已确认无成员
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, zsetKey);
                if (scan.get(2) != null) {
                    return true; // hit-data：ZSCORE 非 null 即成员（部分态下命中同样可信）
                }
                if (Boolean.TRUE.equals(scan.get(3))) {
                    return Boolean.TRUE.equals(dbAnswer.get()); // 部分态：未命中回落 DB
                }
                return false;
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
     * <p><b>部分态（T11-C）</b>：命中数据 key 但带 {@code partial} 标记时**必须先补齐**
     * （见 {@link #completePartial}）再返回——本方法的契约是"返回全部"，返回前缀会让调用方
     * （如 feed 的关注 ids）静默漏人。
     *
     * <p>miss / 降级 / **部分态补齐**三条路径返回 loader 原始顺序（与 {@link SetCache#getMembers}
     * 契约一致，调用方需确定性顺序时自行包装）；hit-data 完整态已是升序。
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
                if (Boolean.TRUE.equals(probe.get(2))) {
                    // 部分态：**直接返回补齐结果**（不回读 Redis）——写回是 best-effort，
                    // 写失败时回读只能拿到前缀，会让 feed 静默漏关注者（见 completePartial）
                    return completePartial(zsetKey, loader, ttlSeconds);
                }
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

    // ==================== 读-按序窗口（分页载体，T7 A1 新增；T11-C 前缀装载） ====================

    /**
     * 按序窗口读：取升序序列中 {@code [offset, offset+count)} 区间 + 总成员数。
     *
     * <p>三态：
     * <ul>
     *   <li>hit-empty → 空窗口（total=0）；</li>
     *   <li>hit-data 且**无** partial 标记（完整）→ 一趟 pipeline {@code ZRANGE + ZCARD}
     *       （与 T7 逐字节一致）；</li>
     *   <li>hit-data 且**有** partial 标记 → 部分态窗口（见 {@link #readPartialWindow}）；
     *       页落在已知前缀内直接 ZRANGE，越界则先补齐；</li>
     *   <li>miss（key 不存在）→ **前缀装载**：DB 只取 {@code [0, offset+count)}
     *       （T11-C 取代 T7 的全量装载）后重读窗口；DB 取满 = 可能还有后续 → 打 partial 标记，
     *       取不满 = 已到底 → 完整态；</li>
     *   <li>Redis 异常 → 降级：**DB 窗口直查**（{@code loader.load(offset, count)}，
     *       单飞去重）+ total 走 {@code totalLoader}，**不装载、不写回**（T11-C 取代
     *       "全量装载 + 内存切片"）。</li>
     * </ul>
     *
     * <p>三路径顺序一致：ZRANGE 的 score 升序 = 成员数值升序 = DB 窗口 SQL 的
     * {@code ORDER BY} 升序。
     *
     * @param offset      起始下标（0 基，越界 → 空列表）
     * @param count       期望条数（≤0 → 空列表）
     * @param loader      DB 窗口装载器（看哪页查哪页）
     * @param totalLoader 部分态 / 降级态的 total 来源（域级计数口径，如 follow 域计数 key）；
     *                    完整态的 total 一律走 ZCARD，不调用本参数
     * @param ttlSeconds  回填 TTL（命中顺带续期，空标记不续）
     */
    public Window getWindow(String zsetKey, long offset, int count, WindowLoader loader,
                            Supplier<Long> totalLoader, long ttlSeconds) {
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
                if (Boolean.TRUE.equals(probe.get(2))) {
                    return readPartialWindow(zsetKey, offset, count, loader, totalLoader, ttlSeconds);
                }
                return readWindow(zsetKey, offset, count);
            }
            // miss：前缀装载（只取 [0, offset+count)），单飞 key 带窗口指纹防不同页串用
            stats.record(CacheStats.Event.MISS, zsetKey);
            int want = (int) Math.min(offset + count, Integer.MAX_VALUE);
            Boolean complete = singleFlight.get(windowFlightKey(zsetKey, offset, count), () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                List<Long> loaded = loader.load(0, want);
                boolean reachedEnd = loaded.size() < want; // DB 返回不足 = 已到底
                writeWindowLoad(zsetKey, loaded, ttlSeconds, reachedEnd);
                return reachedEnd;
            });
            if (Boolean.TRUE.equals(complete)) {
                return readWindow(zsetKey, offset, count); // 完整态：ZCARD 口径（T7 一致）
            }
            return readPartialWindow(zsetKey, offset, count, loader, totalLoader, ttlSeconds);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 窗口读失败，降级 DB, key=" + zsetKey, e);
            stats.record(CacheStats.Event.DEGRADE, zsetKey);
            // 降级不放量（T11-C）：DB **窗口直查**、单飞去重、不装载不写回；total 走域级计数口径
            List<Long> ids = singleFlight.get(windowFlightKey(zsetKey, offset, count), () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                return loader.load(offset, count);
            });
            return new Window(ids, resolveTotal(totalLoader));
        }
    }

    // ==================== 批量-单 zset 多成员（Follow 形态：user:following 对多目标） ====================

    /**
     * 批量查询同一 zset 内多个成员的存在性（一趟 pipeline 探 empty/partial/exists + N×ZSCORE）。
     *
     * <p>空标记 → 全部 false；zset 存在 → 逐个 ZSCORE 判定；miss → dbAnswer 批量作答
     * （DB 为最终真理，失败上抛）+ 单飞全量回填（best-effort，失败仅记日志——DB 答案已返回）；
     * Redis 异常 → 降级：单飞全量装载作答、不写回。
     *
     * <p><b>部分态（T11-C）</b>：{@code partial} 标记存在时 ZSCORE **命中即可信 true**，
     * 未命中则并入 {@code missed} 走既有 {@code dbAnswer} 批量兜底（机制复用，只是触发条件
     * 从"miss"扩到"部分态"）；同时**跳过全量回填**——部分态下回填全量会把装载量重新放大到
     * O(列表总量)，与 T11-C 的目的相悖。
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
        // 部分态标志（lambda 内赋值需要可变持有者）
        boolean[] partialState = {false};
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
                Response<Boolean> exists = p.exists(zsetKey);
                Response<Boolean> partial = p.exists(CacheKeys.partial(zsetKey));
                List<Response<Double>> memberResps = new ArrayList<>(members.size());
                for (Long id : members) {
                    memberResps.add(p.zscore(zsetKey, String.valueOf(id)));
                }
                // 滑动续期：命中 zset 顺带续期（无条件入列，key 不存在返回 0 无效果）；
                // partial 标记同步续期（错位会让前缀被误判为完整集合）；空标记不续
                p.expire(zsetKey, ttlSeconds);
                p.expire(CacheKeys.partial(zsetKey), ttlSeconds);
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
                    boolean partialHit = Boolean.TRUE.equals(partial.get());
                    partialState[0] = partialHit;
                    for (int i = 0; i < members.size(); i++) {
                        Long id = members.get(i);
                        if (memberResps.get(i).get() != null) {
                            result.put(id, true);
                        } else if (partialHit) {
                            missed.add(id); // 部分态：未命中不可信 → 回落 dbAnswer
                        } else {
                            result.put(id, false);
                        }
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
                if (!partialState[0]) {
                    // 同一 key 只回填一次（singleFlight 内 load 全量 + 写 zset）；
                    // best-effort：失败仅记日志，DB 答案照常返回。部分态不回填（见方法注释）
                    try {
                        singleFlight.get(zsetKey, () -> {
                            stats.record(CacheStats.Event.LOAD, zsetKey);
                            Collection<Long> loaded = fullLoader.get();
                            writeZSet(zsetKey, loaded, ttlSeconds);
                            return null;
                        });
                    } catch (RuntimeException e) {
                        // T7：本行堆栈**保留**——该 loader 的"基础设施异常"（TransactionTemplate 包装、
                        // 上游无日志）在此是唯一记录；与源头 SEVERE 的双栈属**不可安全去重的残余**（已登记）
                        LOGGER.log(Level.WARNING, "ZSet 批量回填失败，仅影响缓存, key=" + zsetKey, e);
                    }
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

    // ==================== 内部：部分态（T11-C） ====================

    /**
     * 部分态窗口读：已知集合只是前缀 {@code [0, W)}（W = ZCARD）。
     *
     * <ul>
     *   <li>请求窗口越过 W → 先**前缀补齐**：单飞内读 DB {@code [W, offset+count)} 并 ZADD
     *       追加（不 DEL，合并并发写）；DB 返回不足即到底 → 清 partial 转完整态；
     *       **上界不依赖 total**（DB 返回不足即到底），避免计数漂移影响装载量；</li>
     *   <li>补齐后仍为部分态、或请求窗口本就落在前缀内 → 直接 ZRANGE 该页，
     *       total 取**域级计数口径**（不能用 ZCARD——ZCARD 只是已知前缀大小）。</li>
     * </ul>
     */
    private Window readPartialWindow(String zsetKey, long offset, int count, WindowLoader loader,
                                     Supplier<Long> totalLoader, long ttlSeconds) {
        long known = cardinality(zsetKey);
        if (offset + count > known) {
            Boolean complete = singleFlight.get(windowFlightKey(zsetKey, offset, count), () -> {
                stats.record(CacheStats.Event.LOAD, zsetKey);
                long from = cardinality(zsetKey); // 单飞内重读水位，避免拿陈旧 W 重复追加
                long need = offset + count - from;
                int want = (int) Math.min(Math.max(need, 0L), Integer.MAX_VALUE);
                List<Long> more = (want <= 0) ? Collections.emptyList() : loader.load(from, want);
                boolean reachedEnd = more.size() < want; // want<=0 时不确定是否到底 → 保持部分态
                writeWindowLoad(zsetKey, more, ttlSeconds, reachedEnd);
                return reachedEnd;
            });
            if (Boolean.TRUE.equals(complete)) {
                return readWindow(zsetKey, offset, count); // 补齐后转完整态：ZCARD 口径
            }
        }
        return new Window(readPage(zsetKey, offset, count), resolveTotal(totalLoader));
    }

    /**
     * 部分态**补齐**（T11-C，本设计最危险点）：全量读遇到 {@code partial} 标记时必须补齐，
     * 否则返回的是前缀、调用方（feed 的关注 ids）会**静默漏成员**。
     *
     * <p>补齐走全量 loader（该读本身就要全量），结果 ZADD **合并**（不 DEL，避免丢失并发写）
     * 后清 {@code partial} 标记、转完整态。
     *
     * <p><b>返回值 = DB 装载结果本身（权威答案），绝不"写回后回读 Redis"</b>：
     * 缓存写回是 best-effort（{@link #writeWindowLoad} 失败只记 WRITE_FAIL 不抛出），
     * 一旦写失败，回读 Redis 拿到的仍是被标记为前缀的那部分——那正是本方法要杜绝的静默漏成员。
     * 返回顺序为 loader 原始顺序（DB 全量 SQL 无 ORDER BY），需确定性顺序的调用方自行归一
     * （{@code FollowCache.getFollowingIds} 已用 {@code sortIds} 包裹）。
     */
    private List<Long> completePartial(String zsetKey, Supplier<Collection<Long>> loader, long ttlSeconds) {
        Collection<Long> all = singleFlight.get(zsetKey, () -> {
            stats.record(CacheStats.Event.LOAD, zsetKey);
            Collection<Long> loaded = loader.get();
            writeWindowLoad(zsetKey, loaded, ttlSeconds, true);
            return loaded;
        });
        return new ArrayList<>(all);
    }

    /**
     * 窗口装载/补齐结果写回：非空 → ZADD（合并）+ EXPIRE + partial 标记置/清；
     * 空且 {@code complete} → 确认无数据，写空标记（含 exists 守卫）并清残留标记。
     *
     * <p>Redis 异常 → 记 WRITE_FAIL 不抛出（缓存失败不得导致业务失败；后续读自愈）。
     */
    private void writeWindowLoad(String zsetKey, Collection<Long> members, long ttlSeconds,
                                 boolean complete) {
        if ((members == null || members.isEmpty()) && complete) {
            cacheAside.markEmpty(zsetKey);
            clearPartial(zsetKey); // 防御：数据 key 已过期而旧标记存活时，避免"完整集被当成部分集"
            return;
        }
        try {
            redis.executeVoid(j -> {
                if (members != null && !members.isEmpty()) {
                    Map<String, Double> scored = new LinkedHashMap<>();
                    for (Long m : members) {
                        scored.put(String.valueOf(m), m.doubleValue());
                    }
                    j.zadd(zsetKey, scored);
                }
                j.expire(zsetKey, ttlSeconds);
                if (complete) {
                    j.del(CacheKeys.partial(zsetKey));
                } else {
                    j.setex(CacheKeys.partial(zsetKey), ttlSeconds, CacheKeys.PARTIAL_MARKER_VALUE);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "ZSet 窗口装载写回失败，读自愈, key=" + zsetKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, zsetKey);
        }
    }

    /** 清除部分装载标记（best-effort，不抛出）。 */
    private void clearPartial(String zsetKey) {
        try {
            redis.executeVoid(j -> j.del(CacheKeys.partial(zsetKey)));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "部分装载标记清除失败，读自愈, key=" + zsetKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, zsetKey);
        }
    }

    /**
     * 窗口装载的单飞 key：带**窗口指纹**（{@code key@offset+count}），防同一数据 key 上
     * 不同页的并发请求互相串用装载结果（各自装载量不同）；空标记/全量读仍用裸数据 key，
     * 与两者语义不冲突。
     */
    private static String windowFlightKey(String zsetKey, long offset, int count) {
        return zsetKey + "@" + offset + "+" + count;
    }

    /**
     * 部分态 / 降级态的 total：走调用方注入的**域级计数口径**（如 follow 域的计数 key，
     * 内含与 DB 单列的兜底）。完整态一律用 ZCARD，不经过本方法。
     */
    private long resolveTotal(Supplier<Long> totalLoader) {
        if (totalLoader == null) {
            return 0;
        }
        Long total = totalLoader.get();
        return total == null ? 0 : total;
    }

    // ==================== 内部：三态扫描 / 探测 / 转换 ====================

    /**
     * 单成员三态扫描：一趟 pipeline 返回 [空标记, key 存在, ZSCORE, 部分装载标记]
     * （ZSCORE 可能为 null）。滑动续期：命中顺带续期数据 key 与 partial 标记
     * （无条件入列，key 不存在返回 0 无效果；空标记不续）。
     */
    private List<Object> scanZSet(String zsetKey, String member, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
            Response<Boolean> exists = p.exists(zsetKey);
            Response<Double> score = p.zscore(zsetKey, member);
            Response<Boolean> partial = p.exists(CacheKeys.partial(zsetKey));
            p.expire(zsetKey, ttlSeconds);
            p.expire(CacheKeys.partial(zsetKey), ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), score.get(), partial.get());
        });
    }

    /**
     * 探测：一趟 pipeline 返回 [空标记, key 存在, 部分装载标记]（滑动续期同 {@link #scanZSet}）。
     */
    private List<Boolean> probeZSet(String zsetKey, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(zsetKey));
            Response<Boolean> exists = p.exists(zsetKey);
            Response<Boolean> partial = p.exists(CacheKeys.partial(zsetKey));
            p.expire(zsetKey, ttlSeconds);
            p.expire(CacheKeys.partial(zsetKey), ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), partial.get());
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

    /** 部分态窗口读：只取该页成员（total 不能用 ZCARD——那只是已知前缀 W，见 {@link #readPartialWindow}）。 */
    private List<Long> readPage(String zsetKey, long offset, int count) {
        return redis.execute(j -> toLongList(j.zrange(zsetKey, offset, offset + count - 1L)));
    }

    /** 当前已知成员数（ZCARD）；Redis 异常向上抛，由调用方统一降级。 */
    private long cardinality(String zsetKey) {
        Long card = redis.execute(j -> j.zcard(zsetKey));
        return card == null ? 0 : card;
    }

    /** ZRANGE 结果（String，已按 score 升序）转 Long 列表。 */
    private List<Long> toLongList(List<String> members) {
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            result.add(Long.valueOf(m));
        }
        return result;
    }

    /**
     * 窗口读结果：该页成员（升序）+ 总成员数。
     *
     * <p>total 口径随状态而异：**完整态**与 ids 同源同一 key（ZCARD / 装载结果 size），
     * 调用方可据此拼 {@code PageResult}（totalPages 由页码与页大小推导）；**部分态 / 降级态**
     * 取域级计数口径（成员集只是前缀，ZCARD 会低估）。
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
