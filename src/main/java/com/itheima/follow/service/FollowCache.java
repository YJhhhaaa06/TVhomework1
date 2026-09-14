package com.itheima.follow.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 关注关系缓存（C 周期 T5 新增，NEEDS 4.10）：以用户为中心维护
 * {@code user:following:{userId}} / {@code user:follower:{userId}} 双 Set
 * + MULTI 双写 + 失败双 DEL。
 *
 * <p>与内容/评论/点赞缓存同构（T2/T3/T4 惯例）：缓存类拥有 DAO + TransactionTemplate，
 * 内部完成"缓存优先 → miss 单飞回填 → Redis 异常降级 DB"，业务 Service 读路径只做委托；
 * 写路径在业务 DB 事务提交后调用，缓存失败不抛出（4.2 缓存必须可降级）。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读亦经 {@link SingleFlight} 全量装载作答、
 * 不写回（D4）——同 key 并发读只打一次 DB；失败不以数据形式共享（条目移除，下一请求重试）。
 *
 * <p>key 规范（T1 定稿，见 {@link CacheKeys}）：
 * <ul>
 *   <li>{@code user:following:{userId}}（Set&lt;followedUserId）——我关注了谁；</li>
 *   <li>{@code user:follower:{userId}}（Set&lt;userId）——谁关注了我。</li>
 * </ul>
 *
 * <p>三态读（4.3/4.4）：空标记 {@code empty:user:following:{id}}（写于"确认无关注/无粉丝"）→
 * false/空列表；set 存在 → SISMEMBER/SMEMBERS；miss → 单飞回填（DB 全量 → SADD+EXPIRE，
 * 空集 → 空标记）。关注数/粉丝数计数不入缓存（O-9 二期）。
 *
 * <p>写路径（4.10）：关注/取关在 DB 提交后调用。条件双写——两条 data key 均"已加载
 * （set 存在或空标记存在）"时用 Redis MULTI 原子 SADD/SREM 双写并续 TTL；任一侧为冷 key
 * （未加载）则直接失效（双 DEL 含空标记）让读自愈回填全量，**不创建残缺集**（与 T4
 * 条件写先例同构）；任何 Redis 异常 → 双 DEL（4.10 失败双 DEL），不抛出，DB 为最终真理。
 */
@Component
public class FollowCache {

    private static final Logger LOGGER = LogUtil.getLogger(FollowCache.class);

    private final FollowDao followDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final SingleFlight singleFlight;
    private final CacheAside cacheAside;
    private final CacheStats stats;

    @InjectConstructor
    public FollowCache(FollowDao followDao, TransactionTemplate transactionTemplate,
                       RedisAccess redis, SingleFlight singleFlight, CacheAside cacheAside,
                       CacheStats stats) {
        this.followDao = followDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.singleFlight = singleFlight;
        this.cacheAside = cacheAside;
        this.stats = stats;
    }

    // ==================== 读-单条 isFollowing（三态 + 单飞回填 + 降级） ====================

    /**
     * 查询 userId 是否关注了 followedUserId：
     * hit-empty（空标记）→ false；hit-data（set 存在）→ SISMEMBER；miss → 单飞回填后判成员；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回，4.2 读降级）。
     *
     * @return 是否已关注（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isFollowing(long userId, long followedUserId) {
        String setKey = CacheKeys.userFollowing(userId);
        try {
            List<Boolean> scan = scanSet(setKey, String.valueOf(followedUserId));
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return false; // 空标记：已确认无关注关系
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                return Boolean.TRUE.equals(scan.get(2)); // set 存在：成员判定
            }
            // miss：单飞回填（负载 = DB 全量关注列表 → 写 set 或空标记）
            stats.record(CacheStats.Event.MISS, setKey);
            List<Long> following = singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                List<Long> loaded = loadFollowingIds(userId);
                writeSet(setKey, loaded);
                return loaded;
            });
            return following.contains(followedUserId);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "关注状态缓存读失败，降级 DB, userId=" + userId
                    + ", followedUserId=" + followedUserId, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：降级经单飞全量装载作答（与 miss 回填同 key 同 loader）——
            // 同 key 并发读只打一次 DB；仅装载不写回（D4）
            return loadViaSingleFlight(setKey, () -> loadFollowingIds(userId))
                    .contains(followedUserId);
        }
    }

    // ==================== 读-批量 isFollowing（同一 following set 一趟 pipeline） ====================

    /**
     * 批量查询 userId 对多个用户的关注状态。
     * following set 是用户维度的单一 key：一趟 pipeline 探测 empty/exists + N 个 SISMEMBER；
     * 空标记 → 全部 false；set 存在 → 逐个成员判定；miss → DB {@code getFollowedIds} 兜底
     * + 逐个单飞回填全量（防并发惊群）；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回）。
     *
     * @return 完整 userId → isFollowing 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsFollowing(long userId, List<Long> followedUserIds) {
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String setKey = CacheKeys.userFollowing(userId);
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
        boolean degraded = false;
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
                Response<Boolean> exists = p.exists(setKey);
                List<Response<Boolean>> members = new ArrayList<>(followedUserIds.size());
                for (Long id : followedUserIds) {
                    members.add(p.sismember(setKey, String.valueOf(id)));
                }
                // T9 滑动续期：命中 set 顺带续期（此地已探 empty+exists，set 不存在时 expire 返回 0 无效果；空标记不续）
                p.expire(setKey, ttlSeconds());
                p.sync();
                if (Boolean.TRUE.equals(empty.get())) {
                    stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                    for (Long id : followedUserIds) {
                        result.put(id, false);
                    }
                    return;
                }
                if (Boolean.TRUE.equals(exists.get())) {
                    stats.record(CacheStats.Event.HIT_DATA, setKey);
                    for (int i = 0; i < followedUserIds.size(); i++) {
                        result.put(followedUserIds.get(i), members.get(i).get());
                    }
                    return;
                }
                stats.record(CacheStats.Event.MISS, setKey);
                missed.addAll(followedUserIds);
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量关注状态缓存读失败，全部走 DB, userId=" + userId, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            degraded = true;
            missed.addAll(followedUserIds);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                // 三期 T2 降级不放量：降级经单飞全量装载作答（仅装载不写回，D4）——
                // 同 set key 并发批量读只打一次 DB；不再做降级态必失败的回填写入尝试
                List<Long> following = loadViaSingleFlight(setKey, () -> loadFollowingIds(userId));
                for (Long id : missed) {
                    result.put(id, following.contains(id));
                }
            } else {
                Set<Long> followedSet = loadFollowedIdsByUser(userId, missed);
                for (Long id : missed) {
                    result.put(id, followedSet.contains(id));
                }
                // 同一 following set 只回填一次（singleFlight 内 load 全量 + 写 set），不按 miss 成员逐条重复。
                // best-effort：回填失败（如第二次全量查询 DB 抖动）仅记日志，DB 兜底结果照常返回，不 500。
                try {
                    singleFlight.get(setKey, () -> {
                        stats.record(CacheStats.Event.LOAD, setKey);
                        List<Long> loaded = loadFollowingIds(userId);
                        writeSet(setKey, loaded);
                        return null;
                    });
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "批量关注状态回填失败，仅影响缓存, userId=" + userId, e);
                }
            }
        }
        return result;
    }

    // ==================== 读-关注/粉丝列表（smembers / 空标记 / 单飞回填 / 降级） ====================

    /**
     * 获取用户关注的所有博主 ID（feed 拉取关注列表 / 关注列表页）：
     * hit-empty → 空列表（确认真无）；set 存在 → SMEMBERS（排序列出，确定性顺序）；
     * miss → 单飞回填（DB 全量 → 非空写 set / 空写空标记）；Redis 异常 → DB 降级。
     */
    public List<Long> getFollowingIds(long userId) {
        return getSetMembers(CacheKeys.userFollowing(userId), () -> loadFollowingIds(userId),
                "关注列表");
    }

    /**
     * 获取用户的所有粉丝 ID：逻辑同 {@link #getFollowingIds(long)}，loader 换粉表查询。
     */
    public List<Long> getFollowerIds(long userId) {
        return getSetMembers(CacheKeys.userFollower(userId), () -> loadFollowerIds(userId),
                "粉丝列表");
    }

    private List<Long> getSetMembers(String setKey, Loader loader, String desc) {
        try {
            List<Boolean> probe = redis.execute(j -> {
                Pipeline p = j.pipelined();
                Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
                Response<Boolean> exists = p.exists(setKey);
                // T9 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）
                p.expire(setKey, ttlSeconds());
                p.sync();
                List<Boolean> r = new ArrayList<>(2);
                r.add(empty.get());
                r.add(exists.get());
                return r;
            });
            if (Boolean.TRUE.equals(probe.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return Collections.emptyList(); // 空标记：已确认无数据
            }
            if (Boolean.TRUE.equals(probe.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                Set<String> members = redis.execute(j -> j.smembers(setKey));
                return toSortedLongs(members);
            }
            // miss：单飞回填；返回统一排序（与 SMEMBERS 命中路径一致，避免热/冷读顺序波动）
            stats.record(CacheStats.Event.MISS, setKey);
            return sortIds(singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                List<Long> loaded = loader.load();
                writeSet(setKey, loaded);
                return loaded;
            }));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, desc + "缓存读失败，降级 DB, key=" + setKey, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：降级经单飞（仅装载不写回，D4）——同 key 并发读只打一次 DB
            return sortIds(loadViaSingleFlight(setKey, loader::load));
        }
    }

    // ==================== 写路径（关注/取关，DB 提交后调用 4.10） ====================

    /**
     * 缓存：用户关注他人。条件双写——两条 data key 均"已加载"（set 存在或空标记存在）时
     * MULTI 原子 SADD 双写 + 续 TTL + 解除空标记；任一侧冷 key → 双 DEL 失效让读自愈；
     * Redis 异常 → 双 DEL；全程不抛出（失败由 Cache-Aside 读自愈兜底，关注接口不 500）。
     */
    public void cacheFollow(long userId, long followedUserId) {
        String followingKey = CacheKeys.userFollowing(userId);
        String followerKey = CacheKeys.userFollower(followedUserId);
        try {
            redis.executeVoid(j -> {
                Probe probe = probePair(j, followingKey, followerKey);
                if (!probe.followingReady() || !probe.followerReady()) {
                    // 冷 key：不创建残缺集，双 DEL（含空标记）让读自愈回填全量
                    invalidateKeysQuietly(j, followingKey, followerKey);
                    return;
                }
                Transaction multi = j.multi();
                if (probe.emptyFollowing) {
                    multi.del(CacheKeys.empty(followingKey)); // 解除"无关注"空标记
                }
                multi.sadd(followingKey, String.valueOf(followedUserId));
                if (probe.emptyFollower) {
                    multi.del(CacheKeys.empty(followerKey)); // 解除"无粉丝"空标记
                }
                multi.sadd(followerKey, String.valueOf(userId));
                multi.expire(followingKey, ttlSeconds());
                multi.expire(followerKey, ttlSeconds());
                multi.exec();
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "关注缓存双写失败，双 DEL 生效让读自愈, userId=" + userId
                    + ", followedUserId=" + followedUserId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, followingKey);
            cacheAside.invalidate(followingKey, followerKey);
        }
    }

    /**
     * 缓存：用户取关。条件双写（SREM）+ 续 TTL；空标记命中或冷 key → 双 DEL 失效；
     * Redis 异常 → 双 DEL；全程不抛出。
     */
    public void cacheUnfollow(long userId, long followedUserId) {
        String followingKey = CacheKeys.userFollowing(userId);
        String followerKey = CacheKeys.userFollower(followedUserId);
        try {
            redis.executeVoid(j -> {
                Probe probe = probePair(j, followingKey, followerKey);
                if (!probe.followingReady() || !probe.followerReady()
                        || probe.emptyFollowing || probe.emptyFollower) {
                    // 任一侧确认"无关系"或冷 key：失效整套（含空标记）最安全，读自愈对齐 DB
                    invalidateKeysQuietly(j, followingKey, followerKey);
                    return;
                }
                Transaction multi = j.multi();
                multi.srem(followingKey, String.valueOf(followedUserId));
                multi.srem(followerKey, String.valueOf(userId));
                multi.expire(followingKey, ttlSeconds());
                multi.expire(followerKey, ttlSeconds());
                multi.exec();
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取关缓存双写失败，双 DEL 生效让读自愈, userId=" + userId
                    + ", followedUserId=" + followedUserId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, followingKey);
            cacheAside.invalidate(followingKey, followerKey);
        }
    }

    // ==================== 内部：一条连接上的双 key 探测 ====================

    /** 两条 data key 的状态快照：set 存在性 + 各自空标记。 */
    private static final class Probe {
        final boolean existsFollowing;
        final boolean emptyFollowing;
        final boolean existsFollower;
        final boolean emptyFollower;

        Probe(boolean existsFollowing, boolean emptyFollowing,
              boolean existsFollower, boolean emptyFollower) {
            this.existsFollowing = existsFollowing;
            this.emptyFollowing = emptyFollowing;
            this.existsFollower = existsFollower;
            this.emptyFollower = emptyFollower;
        }

        /** following 侧已加载（set 存在或空标记存在），可安全增量写。 */
        boolean followingReady() {
            return existsFollowing || emptyFollowing;
        }

        /** follower 侧已加载（set 存在或空标记存在），可安全增量写。 */
        boolean followerReady() {
            return existsFollower || emptyFollower;
        }
    }

    private Probe probePair(Jedis j, String followingKey, String followerKey) {
        Pipeline p = j.pipelined();
        Response<Boolean> existsFollowing = p.exists(followingKey);
        Response<Boolean> emptyFollowing = p.exists(CacheKeys.empty(followingKey));
        Response<Boolean> existsFollower = p.exists(followerKey);
        Response<Boolean> emptyFollower = p.exists(CacheKeys.empty(followerKey));
        p.sync();
        return new Probe(existsFollowing.get(), emptyFollowing.get(),
                existsFollower.get(), emptyFollower.get());
    }

    /** 同一连接上 DEL 双 data key 及各自空标记（best-effort，静默）。 */
    private void invalidateKeysQuietly(Jedis j, String... dataKeys) {
        String[] toDel = new String[dataKeys.length * 2];
        for (int i = 0; i < dataKeys.length; i++) {
            toDel[2 * i] = dataKeys[i];
            toDel[2 * i + 1] = CacheKeys.empty(dataKeys[i]);
        }
        j.del(toDel);
    }

    // ==================== 内部：DB 装载（loader，异常抛 ServerException 属真实失败） ====================

    @FunctionalInterface
    private interface Loader {
        List<Long> load();
    }

    private List<Long> loadFollowingIds(long userId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return followDao.getAllFollowedUserIds(conn, userId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "关注列表 DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，查询关注列表失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询关注列表失败", e);
        }
    }

    private List<Long> loadFollowerIds(long userId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return followDao.getFollowerUserIds(conn, userId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "粉丝列表 DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，查询粉丝列表失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询粉丝列表失败", e);
        }
    }

    /** 批量 DB 兜底：userId 关注了 ids 中的哪些（同 {@link FollowDao#getFollowedIds}）。 */
    private Set<Long> loadFollowedIdsByUser(long userId, List<Long> ids) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return followDao.getFollowedIds(conn, userId, ids);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "批量关注状态 DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，批量查询关注状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，批量查询关注状态失败", e);
        }
    }

    // ==================== 内部：回填写入 / 扫描 / 工具（单飞 loader 内调用） ====================

    /**
     * 三期 T2 降级装载（唯一入口，防多处漂移）：经单飞执行 loader 并返回结果——
     * 同 key 并发只打一次 DB；仅装载不写回（D4）；LOAD 由 leader 记一次（与 miss 单飞口径一致）。
     */
    private <T> T loadViaSingleFlight(String setKey, java.util.function.Supplier<T> loader) {
        return singleFlight.get(setKey, () -> {
            stats.record(CacheStats.Event.LOAD, setKey);
            return loader.get();
        });
    }

    /** 关注/粉丝集回填：非空 → SADD-union + EXPIRE；空 → 空标记（统一走 CacheAside.markEmpty）。
     *
     * <p>三期 T4/N3：空集写空标记统一走 {@link CacheAside#markEmpty}（内含"数据 key 不存在
     * 才写"的存在守卫，U-09 定向复用）——原手写块"exists 守卫 + setex + del(setKey)"与公共
     * 实现重复，且守卫内的 del 为死代码（竞态下会删并发刚写入的真数据），随复用一并消除。
     */
    private void writeSet(String setKey, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            cacheAside.markEmpty(setKey);
            return;
        }
        try {
            redis.executeVoid(j -> {
                String[] members = ids.stream().map(String::valueOf).toArray(String[]::new);
                j.sadd(setKey, members);
                j.expire(setKey, ttlSeconds());
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "关注/粉丝集回填失败，读自愈, key=" + setKey, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
        }
    }

    /** 单条成员三态扫描：一趟 pipeline 返回 [空标记, set 存在, 是否成员]（元素可能为 null，用 asList）。
     *  T9 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）。 */
    private List<Boolean> scanSet(String setKey, String member) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
            Response<Boolean> exists = p.exists(setKey);
            Response<Boolean> memberResp = p.sismember(setKey, member);
            p.expire(setKey, ttlSeconds());
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), memberResp.get());
        });
    }

    /** SMEMBERS 原始结果按 id 升序转 List（确定性顺序，不依赖 Redis 无序返回）。 */
    private List<Long> toSortedLongs(Set<String> members) {
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            result.add(Long.valueOf(m));
        }
        Collections.sort(result);
        return result;
    }

    /** List 结果统一升序（miss 回填 / DB 降级路径与 SMEMBERS 命中路径保持一致顺序）。 */
    private List<Long> sortIds(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return copy;
    }

    private long ttlSeconds() {
        return AppConfig.getFollowTtlSeconds();
    }
}