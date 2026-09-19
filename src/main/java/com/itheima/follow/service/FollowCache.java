package com.itheima.follow.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.ZSetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.dao.UserDao;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 关注关系缓存（C 周期 T5 新增，NEEDS 4.10）：以用户为中心维护
 * {@code user:following:{userId}} / {@code user:follower:{userId}} 双 ZSet（有序）
 * + MULTI 双写 + 失败双 DEL。
 *
 * <p>与内容/评论/点赞缓存同构（T2/T3/T4 惯例）：缓存类拥有 DAO + TransactionTemplate，
 * 内部完成"缓存优先 → miss 单飞回填 → Redis 异常降级 DB"，业务 Service 读路径只做委托；
 * 写路径在业务 DB 事务提交后调用，缓存失败不抛出（4.2 缓存必须可降级）。
 *
 * <p>第四期 T3 收口 + 第六期 T7（A1 缓存有序结构）升级：读路径（单成员三态 / 批量判定 /
 * 全量列表 / **按序窗口**）全部经 {@link ZSetCache} 基建组件（与第四期 T1 的 {@code SetCache}
 * 平行，命令层为 ZSet），本类只保留域配置（key 工厂 / DAO loader）+ 写路径双 key 原子语义
 * （MULTI 双写 + 失败双 DEL）。**Set→ZSet 后的有序性红利**：成员 score = 成员数值，
 * 故 ZRANGE 天然升序，与既有 {@link #sortIds} 升序口径**逐条一致**（对外行为零变化）；
 * 窗口读由此只需"定位 + 取 N 条"，不再全量回传（分页成本与列表总量弱相关）。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读亦经单飞全量装载作答、不写回（D4）——
 * 同 key 并发读只打一次 DB；失败不以数据形式共享（条目移除，下一请求重试）。
 *
 * <p>key 规范（T1 定稿，见 {@link CacheKeys}）：
 * <ul>
 *   <li>{@code user:following:{userId}}（ZSet&lt;followedUserId，score=id）——我关注了谁；</li>
 *   <li>{@code user:follower:{userId}}（ZSet&lt;userId，score=id）——谁关注了我；</li>
 *   <li>{@code user:followCount:{userId}} / {@code user:followerCount:{userId}}（String int，
 *       第四期 T6 计数入缓存 R-01：成员/计数分离，与 {@code content:likeCount} 同构，
 *       0 是合法数据，CLI 读走 CacheAside）。</li>
 * </ul>
 *
 * <p>三态读（4.3/4.4）：空标记 {@code empty:user:following:{id}}（写于"确认无关注/无粉丝"）→
 * false/空列表；key 存在 → ZSCORE/ZRANGE；miss → 单飞回填（DB 全量 → ZADD+EXPIRE，
 * 空集 → 空标记）。关注数/粉丝数计数入缓存（第四期 T6 起，R-01；此前不入缓存 O-9 二期）。
 *
 * <p>写路径（4.10）：关注/取关在 DB 提交后调用。条件双写——两条 data key 均"已加载
 * （key 存在或空标记存在）"时用 Redis MULTI 原子 ZADD/ZREM 双写并续 TTL；任一侧为冷 key
 * （未加载）则直接失效（双 DEL 含空标记）让读自愈回填全量，**不创建残缺集**（与 T4
 * 条件写先例同构）；任何 Redis 异常 → 双 DEL（4.10 失败双 DEL），不抛出，DB 为最终真理。
 */
@Component
public class FollowCache {

    private static final Logger LOGGER = LogUtil.getLogger(FollowCache.class);

    /**
     * 计数条件增量（第四期 T6 cache-06，R-01）：count key 存在才 INCRBY（delta=±1）。
     * KEYS: [followCountKey, followerCountKey]；ARGV: [signedDelta]。
     * 镜像 LikeCacheService 条件写先例（4.6）：DB 提交后调用，冷 key（未加载）no-op，
     * 由读回填装载 DB 真理——避免失效-重载竞态（DEL 后再并发读可能写回旧值，TTL 内 stale）。
     */
    static final String FOLLOW_COUNT_ADJUST_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCRBY', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('INCRBY', KEYS[2], ARGV[1]) end";

    private final FollowDao followDao;
    private final UserDao userDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final ZSetCache zSetCache;
    private final CacheAside cacheAside;
    private final CacheStats stats;

    @InjectConstructor
    public FollowCache(FollowDao followDao, UserDao userDao, TransactionTemplate transactionTemplate,
                       RedisAccess redis, ZSetCache zSetCache, CacheAside cacheAside,
                       CacheStats stats) {
        this.followDao = followDao;
        this.userDao = userDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.zSetCache = zSetCache;
        this.cacheAside = cacheAside;
        this.stats = stats;
    }

    // ==================== 读-单条 isFollowing（三态 + 单飞回填 + 降级，经 ZSetCache） ====================

    /**
     * 查询 userId 是否关注了 followedUserId（三态）：经 {@link ZSetCache#isMember}——
     * hit-empty（空标记）→ false；hit-data（key 存在）→ ZSCORE；miss → 单飞回填后判成员；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回，4.2 读降级）。
     *
     * @return 是否已关注（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isFollowing(long userId, long followedUserId) {
        return zSetCache.isMember(CacheKeys.userFollowing(userId), followedUserId,
                () -> loadFollowingIds(userId), ttlSeconds());
    }

    // ==================== 读-批量 isFollowing（同一 following key 一趟 pipeline，经 ZSetCache） ====================

    /**
     * 批量查询 userId 对多个用户的关注状态（经 {@link ZSetCache#batchIsMember}：一趟 pipeline
     * 三态扫描 + DB 批量兜底 answer + 单飞全量回填 best-effort——"answer 查询与回填全量
     * 两趟"结构由组件内保持）；Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，
     * 同 key 并发只打一次 DB；不写回）。
     *
     * @return 完整 userId → isFollowing 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsFollowing(long userId, List<Long> followedUserIds) {
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return zSetCache.batchIsMember(CacheKeys.userFollowing(userId), followedUserIds,
                missed -> loadFollowedIdsByUser(userId, missed),
                () -> loadFollowingIds(userId),
                ttlSeconds());
    }

    // ==================== 读-关注/粉丝列表（ZRANGE / 空标记 / 单飞回填 / 降级，经 ZSetCache） ====================

    /**
     * 获取用户关注的所有博主 ID（feed 拉取关注列表 / 关注列表页）：经 {@link ZSetCache#getMembers}
     * （hit-empty → 空列表；key 存在 → ZRANGE 0 -1；miss → 单飞回填；Redis 异常 → DB 降级），
     * 结果统一 {@link #sortIds} 升序（ZSet score 序已升序，此处为防御性归一，维持既有确定性顺序契约）。
     */
    public List<Long> getFollowingIds(long userId) {
        return sortIds(zSetCache.getMembers(CacheKeys.userFollowing(userId),
                () -> loadFollowingIds(userId), ttlSeconds()));
    }

    /**
     * 获取用户的所有粉丝 ID：逻辑同 {@link #getFollowingIds(long)}，loader 换粉表查询。
     */
    public List<Long> getFollowerIds(long userId) {
        return sortIds(zSetCache.getMembers(CacheKeys.userFollower(userId),
                () -> loadFollowerIds(userId), ttlSeconds()));
    }

    // ==================== 读-关注/粉丝列表「按序窗口」（T7 A1 新增，分页载体） ====================

    /**
     * 取关注列表的**升序窗口**（分页载体，T7 A1）：经 {@link ZSetCache#getWindow} 一趟
     * pipeline 取 {@code [offset, offset+count)} 成员 + 总数（ZCARD），成本与列表总量弱相关
     * （不再全量回传）；三态/单飞/降级语义与全量读同源，**顺序口径一致**（score=id 升序）。
     *
     * @param offset 0 基起始下标；越界 → 空窗口
     */
    public ZSetCache.Window getFollowingWindow(long userId, long offset, int count) {
        return zSetCache.getWindow(CacheKeys.userFollowing(userId), offset, count,
                () -> loadFollowingIds(userId), ttlSeconds());
    }

    /**
     * 取粉丝列表的升序窗口：逻辑同 {@link #getFollowingWindow(long, long, int)}，loader 换粉表查询。
     */
    public ZSetCache.Window getFollowerWindow(long userId, long offset, int count) {
        return zSetCache.getWindow(CacheKeys.userFollower(userId), offset, count,
                () -> loadFollowerIds(userId), ttlSeconds());
    }

    // ==================== 读-关注/粉丝计数（CacheAside：hit-data / miss 单飞回填 / Redis 异常降级 loader） ====================

    /**
     * 查询用户关注数（第四期 T6 计数入缓存 R-01：成员/计数分离，走独立 count key，不装载成员）。
     * 0 是合法 hit-data；Redis 异常由 CacheAside 降级走 DB；DB 为最终真理（Profile 读路径由此命中缓存）。
     */
    public int getFollowCount(long userId) {
        return getCount(CacheKeys.userFollowCount(userId), userId, "关注数", "查询关注数失败",
                (conn, id) -> userDao.getFollowCountById(conn, id));
    }

    /**
     * 查询用户粉丝数（同 {@link #getFollowCount(long)}，key 换 {@code user:followerCount:{userId}}）。
     */
    public int getFollowerCount(long userId) {
        return getCount(CacheKeys.userFollowerCount(userId), userId, "粉丝数", "查询粉丝数失败",
                (conn, id) -> userDao.getFollowerCountById(conn, id));
    }

    /** 计数通用读（CacheAside）：防御分支仅兜底 hit-empty 时 get 返回 null 的 NPE 可能（镜像 LikeCacheService.getLikeCount）。 */
    private int getCount(String countKey, long userId, String label, String errMsg, DaoQuery<Integer> query) {
        Integer cached = cacheAside.get(countKey, Integer.class,
                () -> loadCount(userId, label, errMsg, query), ttlSeconds());
        if (cached != null) {
            return cached;
        }
        stats.record(CacheStats.Event.LOAD, countKey); // 兜底 DB 装载与 CacheAside 口径对齐
        try {
            return loadCount(userId, label, errMsg, query);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new ServerException("服务器异常，" + errMsg, e);
        }
    }

    /** 计数 loader 样板（label 仅用于日志；errMsg 为用户可见异常文案，与 like 域口径一致）。 */
    private int loadCount(long userId, String label, String errMsg, DaoQuery<Integer> query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, userId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + " DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，" + errMsg);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，" + errMsg, e);
        }
    }

    // ==================== 写路径（关注/取关，DB 提交后调用 4.10，双 key 原子语义保持） ====================

    /**
     * 缓存：用户关注他人。条件双写——两条 data key 均"已加载"（key 存在或空标记存在）时
     * MULTI 原子 ZADD 双写（score = 成员数值）+ 续 TTL + 解除空标记；任一侧冷 key → 双 DEL 失效让读自愈；
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
                multi.zadd(followingKey, followedUserId, String.valueOf(followedUserId));
                if (probe.emptyFollower) {
                    multi.del(CacheKeys.empty(followerKey)); // 解除"无粉丝"空标记
                }
                multi.zadd(followerKey, userId, String.valueOf(userId));
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
        // 计数条件增量（T6，R-01）：DB 已提交、关注数各自 +1；冷 key no-op 由读回填；失败只失效计数 key
        adjustCountsQuietly(userId, followedUserId, 1);
    }

    /**
     * 缓存：用户取关。条件双写（ZREM）+ 续 TTL；空标记命中或冷 key → 双 DEL 失效；
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
                multi.zrem(followingKey, String.valueOf(followedUserId));
                multi.zrem(followerKey, String.valueOf(userId));
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
        // 计数条件增量（T6，R-01）：DB 已提交、关注数各自 -1；冷 key no-op 由读回填；失败只失效计数 key
        adjustCountsQuietly(userId, followedUserId, -1);
    }

    // ==================== 内部：计数条件增量（T6，R-01，独立于成员双写块，失败隔离） ====================

    /**
     * 计数条件增量：DB 提交后对 {@code user:followCount:{userId}} /
     * {@code user:followerCount:{followedUserId}} 做 EVAL"exists 才 INCRBY delta"。
     * 任一侧失败 → 失效两个计数 key 让读自愈（4.2 缓存失败不导致业务失败），不抛出。
     */
    private void adjustCountsQuietly(long userId, long followedUserId, int delta) {
        String followCountKey = CacheKeys.userFollowCount(userId);
        String followerCountKey = CacheKeys.userFollowerCount(followedUserId);
        try {
            redis.executeVoid(j -> j.eval(FOLLOW_COUNT_ADJUST_SCRIPT,
                    List.of(followCountKey, followerCountKey),
                    List.of(String.valueOf(delta))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "关注/粉丝计数缓存增量失败，失效计数 key 让读自愈, userId=" + userId
                    + ", followedUserId=" + followedUserId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, followCountKey);
            cacheAside.invalidate(followCountKey, followerCountKey);
        }
    }

    // ==================== 内部：一条连接上的双 key 探测 ====================

    /** 两条 data key 的状态快照：key 存在性 + 各自空标记。 */
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

    // ==================== 内部：DB 装载（loader 样板参数化，异常抛 ServerException 属真实失败） ====================

    /** 单参数查询（关注/粉丝列表 loaders 统一形态：userId → 结果）。 */
    @FunctionalInterface
    private interface DaoQuery<T> {
        T apply(Connection conn, long userId) throws SQLException;
    }

    private List<Long> loadFollowingIds(long userId) {
        return loadIds(userId, "关注列表", "查询关注列表失败",
                (conn, id) -> followDao.getAllFollowedUserIds(conn, id));
    }

    private List<Long> loadFollowerIds(long userId) {
        return loadIds(userId, "粉丝列表", "查询粉丝列表失败",
                (conn, id) -> followDao.getFollowerUserIds(conn, id));
    }

    /** 列表 loader 样板（label 仅用于日志；errMsg 为用户可见异常文案，逐字保持既有）。 */
    private List<Long> loadIds(long userId, String label, String errMsg, DaoQuery<List<Long>> query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, userId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + " DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，" + errMsg);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，" + errMsg, e);
        }
    }

    /** 批量 DB 兜底：userId 关注了 ids 中的哪些（同 {@link FollowDao#getFollowedIds}；DB 即真理，失败上抛）。 */
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

    // ==================== 内部：工具 ====================

    /** List 结果统一升序（全量读三路径与既有 SMEMBERS 命中路径保持一致顺序，确定性输出）。 */
    private List<Long> sortIds(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return copy;
    }

    private long ttlSeconds() {
        return AppConfig.getFollowTtlSeconds();
    }
}
