package com.itheima.like.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.like.dao.CommentLikeDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 点赞缓存服务（C 周期 T4 重制）：计数/成员分离（NEEDS 4.6）+ 统一单飞（4.9）+ 写失败=DEL 降级（4.2）。
 *
 * <p>与内容/评论缓存同构（T2/T3 惯例）：缓存类拥有 DAO + TransactionTemplate，内部完成
 * "缓存优先 → miss 单飞回填 → Redis 异常降级 DB"，业务 Service 读路径只做委托。
 *
 * <p>第四期 T2 收口：原生 Set 成员读路径（单成员三态 / 批量多 set 单成员 / 回填 / 降级装载）
 * 全部经 {@link SetCache} 基建组件（T1，U-09/N3 收敛落点），本类只保留域配置（key 工厂 / DAO
 * loader）+ 计数 CacheAside（JSON int）+ 条件写 Lua。content/comment 孪生方法收敛为 id 维度
 * 参数化单实现。批量回填为 best-effort（SetCache 契约：DB 答案照常返回，缓存失败不导致业务失败，
 * 4.2；T1 登记差异对照见任务清单 T2 执行回写）。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读亦经单飞全量装载作答、不写回（D4）——
 * 同 key 并发读只打一次 DB；失败不以数据形式共享（条目移除，下一请求重试）。
 *
 * <p>key 规范（T1 定稿，见 {@link CacheKeys}）：
 * <ul>
 *   <li>{@code content:likeCount:{id}}（String int，高频读，仅作计数，0 是合法数据）；</li>
 *   <li>{@code content:likeSet:{id}}（原生 Set，低频"谁点过"成员查询，miss 允许穿透）；</li>
 *   <li>{@code comment:likeCount:{id}} / {@code comment:likeSet:{id}} 同理。</li>
 * </ul>
 *
 * <p>三态读成员：空标记（{@code empty:...likeSet:{id}}，写于"确认无点赞者"）→ false；
 * set 存在 → SISMEMBER；miss → 单飞回填（全量点赞者 SADD-union + EXPIRE；空集→空标记，替代
 * 已失效的 {@code __placeholder__} hack（H11））。计数走 CacheAside（Integer JSON），0 不写空标记。
 *
 * <p>写路径（LikeService DB 事务提交后调用）：条件写——仅当 key 已存在才 INCR/DECR/SADD/SREM
 * （三期 T4/N7：Lua 脚本原子执行，消除"探存在→写"竞态窗口，like 两趟往返合并为一趟 EVAL），
 * 冷 key 不创建残缺缓存（防"半套成员/计数"被命中；交给读回填 DB 真理）；any 失败 → 失效 key
 * 让读自愈（4.2），不抛出。计数/成员/空标记全部可降级，Redis 挂不导致点赞接口 500（H5）。
 */
@Component
public class LikeCacheService {

    private static final Logger LOGGER = LogUtil.getLogger(LikeCacheService.class);

    // ==================== 条件写 Lua 脚本（三期 T4/N7 原子化） ====================

    /**
     * 点赞条件写（原子，一趟往返）：清空标记 + "set 存在才 SADD、count 存在才 INCR"。
     * KEYS: [setKey, countKey, emptySetKey]；ARGV: [userId]。
     * 原有"探 exists → 再写"两步非原子（N7）：并发失效 DEL count key 后 INCR 以 1 重建，
     * 计数在 TTL 内对所有人显示错误值——脚本内原子判定彻底消除该窗口。
     * 条件语义内聚于脚本（Redis 服务端执行），单测仅验证调用参数；不设 TTL（由 miss 回填维护，与现状一致）。
     */
    static final String LIKE_CONDITIONAL_SCRIPT =
            "redis.call('DEL', KEYS[3]) "
            + "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SADD', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('INCR', KEYS[2]) end";

    /**
     * 取消点赞条件写（原子）：set 存在才 SREM、count 存在才 DECR（不清空标记——空标记
     * 表示"确认无点赞者"，unlike 不改变该事实，与现状一致）。
     * KEYS: [setKey, countKey]；ARGV: [userId]。DECR 同款竞态（并发失效后以 -1 重建）一并消除。
     */
    static final String UNLIKE_CONDITIONAL_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SREM', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DECR', KEYS[2]) end";

    // ==================== 内部：DAO 查询类型（loader 样板参数化，收敛孪生） ====================

    /** 单参数查询（内容/评论 loaders 统一形态：id → 结果）。 */
    @FunctionalInterface
    private interface DaoQuery<T> {
        T apply(Connection conn, long id) throws SQLException;
    }

    /** 批量 query（批量点赞状态 answer：userId + ids → 命中 id 集合）。 */
    @FunctionalInterface
    private interface BatchQuery {
        Set<Long> apply(Connection conn, long userId, List<Long> ids) throws SQLException;
    }

    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final SetCache setCache;
    private final CacheAside cacheAside;
    private final CacheStats stats;

    @InjectConstructor
    public LikeCacheService(ContentLikeDao contentLikeDao, CommentLikeDao commentLikeDao,
                            TransactionTemplate transactionTemplate, RedisAccess redis,
                            SetCache setCache, CacheAside cacheAside, CacheStats stats) {
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.setCache = setCache;
        this.cacheAside = cacheAside;
        this.stats = stats;
    }

    // ==================== 内容点赞 / 取消（写路径，DB 提交后调用，4.2/4.6） ====================

    /**
     * 缓存：用户给内容点赞。
     * 三期 T4/N7：条件写 Lua 原子化——清空标记 + "set 存在才 SADD、count 存在才 INCR"
     * 一趟 EVAL 原子执行，消除"探存在→写"竞态窗口（并发失效 DEL count key 后 INCR 以 1 重建）；
     * 失败 → 失效 count+set 让读自愈，不抛出。
     */
    public void likeContent(long userId, long contentId) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> j.eval(LIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey, CacheKeys.empty(setKey)),
                    List.of(String.valueOf(userId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    /**
     * 缓存：用户取消内容点赞。
     * 三期 T4/N7：条件写 Lua 原子化——"set 存在才 SREM、count 存在才 DECR"一趟 EVAL
     * 原子执行（DECR 同款竞态：并发失效后以 -1 重建，一并消除）；失败 → 失效 count+set，不抛出。
     */
    public void unlikeContent(long userId, long contentId) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> j.eval(UNLIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey),
                    List.of(String.valueOf(userId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    // ==================== 评论点赞 / 取消（同上） ====================

    /** 缓存：用户给评论点赞（Lua 原子条件写，同 {@link #likeContent}）。 */
    public void likeComment(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> j.eval(LIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey, CacheKeys.empty(setKey)),
                    List.of(String.valueOf(userId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    /** 缓存：用户取消评论点赞（Lua 原子条件写，同 {@link #unlikeContent}）。 */
    public void unlikeComment(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> j.eval(UNLIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey),
                    List.of(String.valueOf(userId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    // ==================== 内容点赞成员（三态 + 单飞 + 降级，经 SetCache） ====================

    /**
     * 查询用户是否点赞了某内容（三态）：经 {@link SetCache#isMember} —— hit-empty（空标记）→ false；
     * hit-data（set 存在）→ SISMEMBER；miss → 单飞回填后判成员；Redis 异常 → 降级 DB
     * （三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回，4.2 读降级）。
     *
     * @param userId 用户
     * @param contentId 内容
     * @return 是否已点赞（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isContentLiked(long userId, long contentId) {
        return setCache.isMember(CacheKeys.contentLikeSet(contentId), userId,
                () -> loadLikers(contentId, "内容",
                        (conn, id) -> contentLikeDao.findLikerIdsByContentId(conn, id)),
                ttlSeconds());
    }

    // ==================== 内容点赞计数（CacheAside：miss 单飞回填 / Redis 异常自动降级 loader） ====================

    /**
     * 查询内容点赞数（计数/成员分离：只走 count key，不加载全量成员）。
     * 0 是合法 hit-data；Redis 异常由 CacheAside 降级走 DB COUNT。
     */
    public int getContentLikeCount(long contentId) {
        return getLikeCount(CacheKeys.contentLikeCount(contentId),
                () -> loadLikeCount(contentId, "内容",
                        (conn, id) -> contentLikeDao.countByContentId(conn, id)));
    }

    // ==================== 评论点赞成员 / 计数（同内容，key 换 comment） ====================

    public boolean isCommentLiked(long userId, long commentId) {
        return setCache.isMember(CacheKeys.commentLikeSet(commentId), userId,
                () -> loadLikers(commentId, "评论",
                        (conn, id) -> commentLikeDao.findLikerIdsByCommentId(conn, id)),
                ttlSeconds());
    }

    public int getCommentLikeCount(long commentId) {
        return getLikeCount(CacheKeys.commentLikeCount(commentId),
                () -> loadLikeCount(commentId, "评论",
                        (conn, id) -> commentLikeDao.countByCommentId(conn, id)));
    }

    // ==================== 批量点赞状态（pipeline + DB 兜底，经 SetCache.batchKeysIsMember） ====================

    /**
     * 批量查询用户对多个内容的点赞状态（经 {@link SetCache#batchKeysIsMember}：一趟 pipeline
     * 三态扫描 + DB 批量兜底 answer + 逐 key 单飞回填 best-effort）；Redis 异常 → 降级 DB
     * （三期 T2：逐 key 单飞全量装载作答，同 key 并发只打一次 DB；不写回）。
     *
     * @return 完整 contentId → isLiked 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> idToKey = new HashMap<>();
        Map<String, Long> keyToId = new HashMap<>();
        List<String> setKeys = new ArrayList<>(contentIds.size());
        for (Long cid : contentIds) {
            String setKey = CacheKeys.contentLikeSet(cid);
            idToKey.put(cid, setKey);
            keyToId.put(setKey, cid);
            setKeys.add(setKey);
        }
        Map<String, Boolean> byKey = setCache.batchKeysIsMember(setKeys, userId,
                missedKeys -> {
                    List<Long> missedIds = new ArrayList<>(missedKeys.size());
                    for (String k : missedKeys) {
                        missedIds.add(keyToId.get(k));
                    }
                    Set<Long> likedIds = queryLikedIds(userId, missedIds, "内容",
                            (conn, uid, ids) -> contentLikeDao.findLikedContentIds(conn, uid, ids));
                    Set<String> likedKeys = new HashSet<>();
                    for (Long id : likedIds) {
                        likedKeys.add(CacheKeys.contentLikeSet(id));
                    }
                    return likedKeys;
                },
                setKey -> loadLikers(keyToId.get(setKey), "内容",
                        (conn, id) -> contentLikeDao.findLikerIdsByContentId(conn, id)),
                ttlSeconds());
        Map<Long, Boolean> result = new HashMap<>();
        for (Long cid : contentIds) {
            result.put(cid, byKey.get(idToKey.get(cid)));
        }
        return result;
    }

    /**
     * 批量查询用户对多个评论的点赞状态（逻辑同内容批量）；
     * Redis 异常 → 降级 DB（三期 T2：逐 key 单飞全量装载作答，不写回）。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> idToKey = new HashMap<>();
        Map<String, Long> keyToId = new HashMap<>();
        List<String> setKeys = new ArrayList<>(commentIds.size());
        for (Long cid : commentIds) {
            String setKey = CacheKeys.commentLikeSet(cid);
            idToKey.put(cid, setKey);
            keyToId.put(setKey, cid);
            setKeys.add(setKey);
        }
        Map<String, Boolean> byKey = setCache.batchKeysIsMember(setKeys, userId,
                missedKeys -> {
                    List<Long> missedIds = new ArrayList<>(missedKeys.size());
                    for (String k : missedKeys) {
                        missedIds.add(keyToId.get(k));
                    }
                    Set<Long> likedIds = queryLikedIds(userId, missedIds, "评论",
                            (conn, uid, ids) -> commentLikeDao.findLikedCommentIds(conn, uid, ids));
                    Set<String> likedKeys = new HashSet<>();
                    for (Long id : likedIds) {
                        likedKeys.add(CacheKeys.commentLikeSet(id));
                    }
                    return likedKeys;
                },
                setKey -> loadLikers(keyToId.get(setKey), "评论",
                        (conn, id) -> commentLikeDao.findLikerIdsByCommentId(conn, id)),
                ttlSeconds());
        Map<Long, Boolean> result = new HashMap<>();
        for (Long cid : commentIds) {
            result.put(cid, byKey.get(idToKey.get(cid)));
        }
        return result;
    }

    // ==================== 失效（内容/评论删除级联，4.5 业务显式失效） ====================

    /**
     * 删除内容后失效其点赞缓存（计数 + 成员 + 空标记）。
     * 调用方：LikeService.deleteContentLike（ContentService 内容删除/下架级联，T6 自 ContentCacheManager 迁入）。
     */
    public void deleteContentLike(long contentId) {
        cacheAside.invalidate(CacheKeys.contentLikeCount(contentId), CacheKeys.contentLikeSet(contentId));
    }

    /**
     * 删除评论后失效其点赞缓存（计数 + 成员 + 空标记）。
     */
    public void deleteCommentLike(long commentId) {
        cacheAside.invalidate(CacheKeys.commentLikeCount(commentId), CacheKeys.commentLikeSet(commentId));
    }

    // ==================== 内部：DB 装载（loader 样板参数化；异常抛 ServerException 属真实失败） ====================

    /** 成员 loader：内容/评论共用一个实现（label 仅用于日志）。 */
    private Set<Long> loadLikers(long id, String label, DaoQuery<Set<Long>> query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, id);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + "点赞者 DB 查询失败, id=" + id, e);
                    throw new ServerException("服务器异常，查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞状态失败", e);
        }
    }

    /** 计数 loader：内容/评论共用一个实现（label 仅用于日志）。 */
    private int loadLikeCount(long id, String label, DaoQuery<Integer> query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, id);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + "点赞数 DB 查询失败, id=" + id, e);
                    throw new ServerException("服务器异常，查询点赞数失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞数失败", e);
        }
    }

    /** 批量点赞状态 answer：DB 即真理，失败上抛（无答案可答）。内容/评论共用一个实现。 */
    private Set<Long> queryLikedIds(long userId, List<Long> ids, String label, BatchQuery query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, userId, ids);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + "批量点赞状态 DB 查询失败, userId=" + userId, e);
                    throw new ServerException("服务器异常，批量查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，批量查询点赞状态失败", e);
        }
    }

    /** 计数通用读（CacheAside）：防御分支仅兜底 hit-empty 时 get 返回 null 的 NPE 可能。 */
    private int getLikeCount(String countKey, Callable<Integer> loader) {
        Integer cached = cacheAside.get(countKey, Integer.class, loader, ttlSeconds());
        if (cached != null) {
            return cached;
        }
        stats.record(CacheStats.Event.LOAD, countKey); // 兜底 DB 装载与 CacheAside 口径对齐
        try {
            return loader.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long ttlSeconds() {
        return AppConfig.getLikeTtlSeconds();
    }
}