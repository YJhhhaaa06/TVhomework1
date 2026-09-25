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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 原生 Set 缓存基建组件（第四期 T1 新增，U-09/N3 收敛落点）：把各域重复实现的
 * "三态读（含单成员判定）/ 全量读 / 回填（含空标记）/ 批量判定 / 降级单飞装载"
 * 收敛为 cache 包内一处，后续 {@code LikeCacheService}/{@code FollowCache}（T2/T3）收口统一落点。
 *
 * <p>与 {@link CacheAside} 的边界：CacheAside 管 JSON String 的 Cache-Aside；
 * 本组件管原生 Set（SISMEMBER/SMEMBERS/SADD），两者平行存在、互不修改对方签名。
 *
 * <p>语义对齐现状（红线：key 命名 / 三态语义 / 空标记 TTL / 降级语义一概不变）：
 * <ul>
 *   <li>三态顺序：先空标记（{@code empty:{setKey}}）→ 后数据 → miss 才单飞回填；</li>
 *   <li>统计打点：六事件 key = 数据 setKey；批量打点粒度各自保持（单 set 多成员一趟记一次 /
 *       多 set 单成员每 (key, 决策) 记一次）；</li>
 *   <li>滑动续期：探针无条件入列 {@code expire(setKey, ttl)}（精确 TTL 无抖动），
 *       空标记 key 从不续期（4.14）；</li>
 *   <li>回填：非空 SADD + EXPIRE；空 → 统一 {@link CacheAside#markEmpty}（exists 守卫同源，三期 T4/N3）；</li>
 *   <li>降级不放量（三期 T2）：Redis 异常经单飞全量装载作答、不写回（D4）；loader 失败以异常收场。</li>
 * </ul>
 *
 * <p>批量 miss 语义：DB 答案查询（dbAnswer）失败 → 上抛（DB 即真理，无答案可答）；
 * 答案返回后回填写 best-effort——失败仅记日志、DB 答案照常返回（4.2 缓存失败不得导致业务失败）。
 */
@Component
public class SetCache {

    private static final Logger LOGGER = LogUtil.getLogger(SetCache.class);

    private final RedisAccess redis;
    private final CacheAside cacheAside;
    private final SingleFlight singleFlight;
    private final CacheStats stats;

    @InjectConstructor
    public SetCache(RedisAccess redis, CacheAside cacheAside, SingleFlight singleFlight,
                    CacheStats stats) {
        this.redis = redis;
        this.cacheAside = cacheAside;
        this.singleFlight = singleFlight;
        this.stats = stats;
    }

    // ==================== 读-单成员三态（含单飞回填 + 降级） ====================

    /**
     * 查询 {@code member} 是否属于 {@code setKey}：
     * hit-empty（空标记）→ false；hit-data（set 存在）→ SISMEMBER；
     * miss → 单飞回填（loader 全量装载 → 写 set 或空标记）后判成员；
     * Redis 异常 → 降级（三期 T2 口径：经单飞全量装载作答，同 key 并发只打一次 DB；仅装载不写回，D4）。
     *
     * @return 是否成员（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isMember(String setKey, long member, Supplier<Collection<Long>> loader,
                            long ttlSeconds) {
        try {
            List<Boolean> scan = scanSet(setKey, String.valueOf(member), ttlSeconds);
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return false; // 空标记：已确认无成员
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                return Boolean.TRUE.equals(scan.get(2)); // set 存在：成员判定
            }
            // miss：单飞回填（负载 = DB 全量成员 → 写 set 或空标记）
            stats.record(CacheStats.Event.MISS, setKey);
            Collection<Long> members = singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                Collection<Long> loaded = loader.get();
                writeSet(setKey, loaded, ttlSeconds);
                return loaded;
            });
            return members.contains(member);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "Set 缓存读失败，降级 DB, key=" + setKey, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：降级经单飞全量装载作答（与 miss 回填同 key 同 loader）——不写回（D4）
            return loadViaSingleFlight(setKey, loader).contains(member);
        }
    }

    // ==================== 读-全量（三态 + 单飞回填 + 降级） ====================

    /**
     * 读取 set 全量成员：hit-empty → 空列表（确认真无）；set 存在 → SMEMBERS；
     * miss → 单飞回填（DB 全量 → 非空写 set / 空写空标记）；Redis 异常 → DB 降级。
     *
     * <p>不做排序（返回 loader / Redis 原始顺序）；需确定性顺序的调用方自行包装（如 follow 域升序）。
     * 注意：hit-data（SMEMBERS 无序）/ miss（loader 序）/ 降级（loader 序）三路径均不排序，
     * 收口方（T3 FollowCache）须在同一包装点统一排序，防热/冷读顺序波动。
     */
    public List<Long> getMembers(String setKey, Supplier<Collection<Long>> loader,
                                 long ttlSeconds) {
        try {
            List<Boolean> probe = probeSet(setKey, ttlSeconds);
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return Collections.emptyList(); // 空标记：已确认无数据
            }
            if (Boolean.TRUE.equals(probe.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                Set<String> members = redis.execute(j -> j.smembers(setKey));
                return toLongList(members);
            }
            // miss：单飞回填；返回 loader 顺序（与调用方约定，不排序）
            stats.record(CacheStats.Event.MISS, setKey);
            return new ArrayList<>(singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                Collection<Long> loaded = loader.get();
                writeSet(setKey, loaded, ttlSeconds);
                return loaded;
            }));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "Set 全量读失败，降级 DB, key=" + setKey, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：降级经单飞（仅装载不写回，D4）
            return new ArrayList<>(loadViaSingleFlight(setKey, loader));
        }
    }

    // ==================== 批量-单 set 多成员（Follow 形态：user:following 对多目标） ====================

    /**
     * 批量查询同一 set 内多个成员的存在性（一趟 pipeline 探 empty/exists + N×SISMEMBER）。
     *
     * <p>空标记 → 全部 false；set 存在 → 逐个成员判定；miss → dbAnswer 批量作答
     * （哪些成员存在，DB 为最终真理，失败上抛）+ 单飞全量回填（best-effort，失败仅记日志，
     * 4.2 缓存失败不得导致业务失败——DB 答案已返回）；Redis 异常 → 降级：单飞全量装载作答、不写回。
     *
     * @param dbAnswer miss 答案函数（不得返回 null；返回空 Set 表示无命中成员）
     */
    public Map<Long, Boolean> batchIsMember(String setKey, List<Long> members,
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
                Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
                Response<Boolean> exists = p.exists(setKey);
                List<Response<Boolean>> memberResps = new ArrayList<>(members.size());
                for (Long id : members) {
                    memberResps.add(p.sismember(setKey, String.valueOf(id)));
                }
                // 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）
                p.expire(setKey, ttlSeconds);
                p.sync();
                if (Boolean.TRUE.equals(empty.get())) {
                    stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                    for (Long id : members) {
                        result.put(id, false);
                    }
                    return;
                }
                if (Boolean.TRUE.equals(exists.get())) {
                    stats.record(CacheStats.Event.HIT_DATA, setKey);
                    for (int i = 0; i < members.size(); i++) {
                        result.put(members.get(i), memberResps.get(i).get());
                    }
                    return;
                }
                stats.record(CacheStats.Event.MISS, setKey);
                missed.addAll(members);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "Set 批量读失败，全部走 DB, key=" + setKey, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            degraded = true;
            missed.addAll(members);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                // 降级不放量：单飞全量装载作答（仅装载不写回，D4）
                Collection<Long> all = loadViaSingleFlight(setKey, fullLoader);
                for (Long id : missed) {
                    result.put(id, all.contains(id));
                }
            } else {
                Set<Long> answered = dbAnswer.apply(missed);
                for (Long id : missed) {
                    result.put(id, answered.contains(id));
                }
                // 同一 set 只回填一次（singleFlight 内 load 全量 + 写 set）；best-effort：失败仅记日志，DB 答案照常返回
                try {
                    singleFlight.get(setKey, () -> {
                        stats.record(CacheStats.Event.LOAD, setKey);
                        Collection<Long> loaded = fullLoader.get();
                        writeSet(setKey, loaded, ttlSeconds);
                        return null;
                    });
                } catch (RuntimeException e) {
                    // T11 定栈：本行只记结论（不带栈）——T7 登记的"回填链双栈残余"已随 T11 收口：
                    // DAO 级失败由 loader 源头 SEVERE 持栈，事务基础设施失败由 TransactionTemplate 持栈
                    LOGGER.log(Level.WARNING, "Set 批量回填失败，仅影响缓存, key=" + setKey);
                }
            }
        }
        return result;
    }

    // ==================== 批量-多 set 单成员（原 Like 形态：逐 key 判定同 userId；T4 反转后无生产调用，预留组件 API） ====================

    /**
     * 批量查询同一成员在多个 set key 中的存在性（每 key 一趟 pipeline 探 empty/exists + SISMEMBER）。
     *
     * <p>三态逐 key 判定（打点粒度 = 每 (key, 决策) 记一次）；miss → dbAnswer 批量作答
     * （哪些 key 有该成员，DB 为最终真理，失败上抛）+ 逐 key 单飞全量回填（best-effort）；
     * Redis 异常 → 降级：逐 key 单飞全量装载作答、不写回。
     *
     * <p>注（第四期 T4 后）：点赞成员 key 已反转为用户维度（单 set 多成员 {@link #batchIsMember}），
     * 本方法已无生产调用方，作为组件通用 API 预留复用（不删除，防未来形态回退）。
     *
     * @param dbAnswer miss 答案函数（不得返回 null；返回空 Set 表示无命中 key）
     */
    public Map<String, Boolean> batchKeysIsMember(List<String> setKeys, long member,
                                                  Function<List<String>, Set<String>> dbAnswer,
                                                  Function<String, Collection<Long>> keyLoader,
                                                  long ttlSeconds) {
        if (setKeys == null || setKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Boolean> result = new HashMap<>();
        List<String> missed = new ArrayList<>();
        boolean degraded = false;
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                List<Response<Boolean>> empties = new ArrayList<>();
                List<Response<Boolean>> existsList = new ArrayList<>();
                List<Response<Boolean>> memberResps = new ArrayList<>();
                for (String setKey : setKeys) {
                    empties.add(p.exists(CacheKeys.empty(setKey)));
                    existsList.add(p.exists(setKey));
                    memberResps.add(p.sismember(setKey, String.valueOf(member)));
                    // 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）
                    p.expire(setKey, ttlSeconds);
                }
                p.sync();
                for (int i = 0; i < setKeys.size(); i++) {
                    String setKey = setKeys.get(i);
                    if (Boolean.TRUE.equals(empties.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                        result.put(setKey, false);
                    } else if (Boolean.TRUE.equals(existsList.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_DATA, setKey);
                        result.put(setKey, memberResps.get(i).get());
                    } else {
                        stats.record(CacheStats.Event.MISS, setKey);
                        missed.add(setKey);
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "Set 批量读失败，全部走 DB, keys=" + setKeys.size(), e);
            for (String setKey : setKeys) {
                stats.record(CacheStats.Event.DEGRADE, setKey);
            }
            degraded = true;
            missed.addAll(setKeys);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                for (String setKey : missed) {
                    Collection<Long> all = loadViaSingleFlight(setKey, () -> keyLoader.apply(setKey));
                    result.put(setKey, all.contains(member));
                }
            } else {
                Set<String> answered = dbAnswer.apply(missed);
                for (String setKey : missed) {
                    result.put(setKey, answered.contains(setKey));
                }
                for (String setKey : missed) {
                    // best-effort：回填失败仅记日志，DB 答案照常返回，不 500（4.2）
                    try {
                        singleFlight.get(setKey, () -> {
                            stats.record(CacheStats.Event.LOAD, setKey);
                            Collection<Long> loaded = keyLoader.apply(setKey);
                            writeSet(setKey, loaded, ttlSeconds);
                            return null;
                        });
                    } catch (RuntimeException e) {
                        // T11 定栈：本行只记结论（不带栈），同 batchIsMember——堆栈由 loader 源头 / TransactionTemplate 持有
                        // （该重载无生产调用方，一并按规则处理）
                        LOGGER.log(Level.WARNING, "Set 批量回填失败，仅影响缓存, key=" + setKey);
                    }
                }
            }
        }
        return result;
    }

    // ==================== 回填（单飞 loader 内调用；空集 → 空标记） ====================

    /**
     * Set 成员回填：非空 → SADD-union + EXPIRE（精确 TTL 无抖动；不 DEL，避免丢失并发 SADD）；
     * 空 → 统一走 {@link CacheAside#markEmpty}（内含"数据 key 不存在才写"的存在守卫，三期 T4/N3）。
     * Redis 异常 → 记 WRITE_FAIL，不抛出（读路径视同 miss 走 DB 自愈）。
     */
    public void writeSet(String setKey, Collection<Long> members, long ttlSeconds) {
        if (members == null || members.isEmpty()) {
            cacheAside.markEmpty(setKey);
            return;
        }
        try {
            redis.executeVoid(j -> {
                String[] arr = new String[members.size()];
                int i = 0;
                for (Long m : members) {
                    arr[i++] = String.valueOf(m);
                }
                j.sadd(setKey, arr);
                j.expire(setKey, ttlSeconds);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "Set 回填失败，读自愈, key=" + setKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
        }
    }

    // ==================== 降级装载（三期 T2 唯一入口，防多处漂移） ====================

    /**
     * 降级装载：经单飞执行 loader 并返回结果——同 key 并发只打一次 DB；仅装载不写回（D4）；
     * LOAD 由 leader 记一次（与 miss 单飞口径一致）。
     */
    public <T> T loadViaSingleFlight(String setKey, Supplier<T> loader) {
        return singleFlight.get(setKey, () -> {
            stats.record(CacheStats.Event.LOAD, setKey);
            return loader.get();
        });
    }

    // ==================== 内部：三态扫描 / 探测 / 转换 ====================

    /**
     * 单成员三态扫描：一趟 pipeline 返回 [空标记, set 存在, 是否成员]（元素可能为 null，用 asList）。
     * 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）。
     */
    private List<Boolean> scanSet(String setKey, String member, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
            Response<Boolean> exists = p.exists(setKey);
            Response<Boolean> memberResp = p.sismember(setKey, member);
            p.expire(setKey, ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), memberResp.get());
        });
    }

    /** 全量读探测：一趟 pipeline 返回 [空标记, set 存在]（滑动续期同 {@link #scanSet}）。 */
    private List<Boolean> probeSet(String setKey, long ttlSeconds) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
            Response<Boolean> exists = p.exists(setKey);
            p.expire(setKey, ttlSeconds);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get());
        });
    }

    /** SMEMBERS 原始结果（String）转 Long 列表（不排序；顺序由调用方约定）。 */
    private List<Long> toLongList(Set<String> members) {
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            result.add(Long.valueOf(m));
        }
        return result;
    }
}
