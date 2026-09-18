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
import java.util.Collections;
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
 * <p>第四期 T4 装载反转（R-08，用户 2026-09-15 拍板：内容+评论全反转）：点赞成员 key 由
 * 内容/评论维度 {@code content:likeSet:{id}} / {@code comment:likeSet:{id}}（装载量 = 该内容/评论
 * 的点赞者数）反转为**用户维度** {@code user:likeSet:{userId}}（Set&lt;contentId）与
 * {@code user:commentLikeSet:{userId}}（Set&lt;commentId），与 {@code user:following} 同构——
 * 「我是否点过赞」的装载量从内容热度维度变为该用户点赞数维度，与热门内容解耦（R-08 目标）。
 * 批量判定由「多 set 单成员」（{@code SetCache.batchKeysIsMember}）收敛为「单 set 多成员」
 * （{@code SetCache.batchIsMember}）——命令数与装载量双降；计数 key（content:likeCount /
 * comment:likeCount）不动；旧 key 不双写、TTL 自然回收；内容/评论删除仅失效计数 key（成员 key
 * 为用户维度，删除无法反查点赞者，残留成员指向已删除 id、不复用不外显，见失效节 javadoc）。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读亦经单飞全量装载作答、不写回（D4）——
 * 同 key 并发读只打一次 DB；失败不以数据形式共享（条目移除，下一请求重试）。
 *
 * <p>key 规范（T1 定稿 + T4 反转，见 {@link CacheKeys}）：
 * <ul>
 *   <li>{@code content:likeCount:{id}} / {@code comment:likeCount:{id}}（String int，高频读，
 *       仅作计数，0 是合法数据，不动）；</li>
 *   <li>{@code user:likeSet:{userId}}（原生 Set&lt;contentId，T4 起内容点赞成员；
 *       旧 {@code content:likeSet:{contentId}} 不双写、TTL 自然回收）；</li>
 *   <li>{@code user:commentLikeSet:{userId}}（原生 Set&lt;commentId，T4 起评论点赞成员；
 *       旧 {@code comment:likeSet:{commentId}} 不双写、TTL 自然回收）。</li>
 * </ul>
 *
 * <p>三态读成员：空标记（{@code empty:user:likeSet:{userId}} 等，写于"确认该用户无点赞"）→ false；
 * set 存在 → SISMEMBER；miss → 单飞回填（用户全量点赞 id SADD-union + EXPIRE；空集→空标记，替代
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
     * KEYS: [setKey, countKey, emptySetKey]；ARGV: [memberId]（T4 反转后为 contentId/commentId）。
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
     * 表示"确认无点赞"，unlike 不改变该事实，与现状一致）。
     * KEYS: [setKey, countKey]；ARGV: [memberId]（T4 反转后为 contentId/commentId）。
     * DECR 同款竞态（并发失效后以 -1 重建）一并消除。
     */
    static final String UNLIKE_CONDITIONAL_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SREM', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DECR', KEYS[2]) end";

    // ==================== 内部：DAO 查询类型（loader 样板参数化，收敛孪生） ====================

    /** 单参数查询（内容/评论成员 loaders 统一形态：userId → 用户点赞的 id 集合）。 */
    @FunctionalInterface
    private interface DaoQuery<T> {
        T apply(Connection conn, long userId) throws SQLException;
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

    // ==================== 内容点赞 / 取消（写路径，DB 提交后调用，4.2/4.6；T4 反转） ====================

    /**
     * 缓存：用户给内容点赞。
     * T4 反转：成员写用户维度 {@code user:likeSet:{userId}}（SADD contentId），计数仍写
     * {@code content:likeCount:{contentId}}（INCR）——脚本常量与语义不变，仅 KEYS/ARGV 换维度。
     * 三期 T4/N7：条件写 Lua 原子化——清空标记 + "set 存在才 SADD、count 存在才 INCR"一趟 EVAL
     * 原子执行，消除"探存在→写"竞态窗口（并发失效 DEL count key 后 INCR 以 1 重建）；
     * 失败 → 失效 count key 让读自愈，不抛出。
     */
    public void likeContent(long userId, long contentId) {
        String setKey = CacheKeys.userLikeSet(userId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> j.eval(LIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey, CacheKeys.empty(setKey)),
                    List.of(String.valueOf(contentId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey);
        }
    }

    /**
     * 缓存：用户取消内容点赞。
     * T4 反转：成员写 {@code user:likeSet:{userId}}（SREM contentId），计数写
     * {@code content:likeCount:{contentId}}（DECR）；脚本常量与语义不变，仅 KEYS/ARGV 换维度。
     * 三期 T4/N7：条件写 Lua 原子化——"set 存在才 SREM、count 存在才 DECR"一趟 EVAL
     * 原子执行（DECR 同款竞态：并发失效后以 -1 重建，一并消除）；失败 → 失效 count key，不抛出。
     */
    public void unlikeContent(long userId, long contentId) {
        String setKey = CacheKeys.userLikeSet(userId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> j.eval(UNLIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey),
                    List.of(String.valueOf(contentId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey);
        }
    }

    // ==================== 评论点赞 / 取消（同上，T4 反转） ====================

    /** 缓存：用户给评论点赞（Lua 原子条件写，成员写 {@code user:commentLikeSet:{userId}}，同 {@link #likeContent}）。 */
    public void likeComment(long userId, long commentId) {
        String setKey = CacheKeys.userCommentLikeSet(userId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> j.eval(LIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey, CacheKeys.empty(setKey)),
                    List.of(String.valueOf(commentId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey);
        }
    }

    /** 缓存：用户取消评论点赞（Lua 原子条件写，成员写 {@code user:commentLikeSet:{userId}}，同 {@link #unlikeContent}）。 */
    public void unlikeComment(long userId, long commentId) {
        String setKey = CacheKeys.userCommentLikeSet(userId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> j.eval(UNLIKE_CONDITIONAL_SCRIPT,
                    List.of(setKey, countKey),
                    List.of(String.valueOf(commentId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
            cacheAside.invalidate(countKey);
        }
    }

    // ==================== 内容点赞成员（T4 反转后用户维度；三态 + 单飞 + 降级，经 SetCache） ====================

    /**
     * 查询用户是否点赞了某内容（三态，T4 反转）：经 {@link SetCache#isMember} 判
     * {@code user:likeSet:{userId}} 是否含 {@code contentId} —— hit-empty（"该用户无点赞"）→ false；
     * hit-data（set 存在）→ SISMEMBER；miss → 单飞回填（该用户点赞的全部内容）后判成员；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回，4.2 读降级）。
     *
     * @param userId 用户
     * @param contentId 内容
     * @return 是否已点赞（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isContentLiked(long userId, long contentId) {
        return setCache.isMember(CacheKeys.userLikeSet(userId), contentId,
                () -> loadUserLikeIds(userId, "内容",
                        (conn, uid) -> contentLikeDao.findLikedContentIdsByUser(conn, uid)),
                ttlSeconds());
    }

    // ==================== 内容点赞计数（CacheAside：miss 单飞回填 / Redis 异常自动降级 loader） ====================

    /**
     * 查询内容点赞数（计数/成员分离：只走 count key，不加载全量成员；T4 不动）。
     * 0 是合法 hit-data；Redis 异常由 CacheAside 降级走 DB COUNT。
     */
    public int getContentLikeCount(long contentId) {
        return getLikeCount(CacheKeys.contentLikeCount(contentId),
                () -> loadLikeCount(contentId, "内容",
                        (conn, id) -> contentLikeDao.countByContentId(conn, id)));
    }

    // ==================== 评论点赞成员 / 计数（T4 反转后用户维度；同内容，key 换 userCommentLikeSet） ====================

    /** 查询用户是否点赞了某评论（三态，T4 反转）：判 {@code user:commentLikeSet:{userId}} 是否含 {@code commentId}。 */
    public boolean isCommentLiked(long userId, long commentId) {
        return setCache.isMember(CacheKeys.userCommentLikeSet(userId), commentId,
                () -> loadUserLikeIds(userId, "评论",
                        (conn, uid) -> commentLikeDao.findLikedCommentIdsByUser(conn, uid)),
                ttlSeconds());
    }

    public int getCommentLikeCount(long commentId) {
        return getLikeCount(CacheKeys.commentLikeCount(commentId),
                () -> loadLikeCount(commentId, "评论",
                        (conn, id) -> commentLikeDao.countByCommentId(conn, id)));
    }

    // ==================== 批量点赞状态（T4 反转后单 set 多成员，经 SetCache.batchIsMember） ====================

    /**
     * 批量查询用户对多个内容的点赞状态（T4 反转：经 {@link SetCache#batchIsMember} 一趟 pipeline
     * 判 {@code user:likeSet:{userId}} 对 N 个 contentId——1 组探针 + N×SISMEMBER + 1 次续期，
     * 替代原「多 set 单成员」逐 key 判定；miss → DB 批量兜底 answer + 单飞全量回填 best-effort）；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回）。
     *
     * @return 完整 contentId → isLiked 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return setCache.batchIsMember(CacheKeys.userLikeSet(userId), contentIds,
                missedIds -> queryLikedIds(userId, missedIds, "内容",
                        (conn, uid, ids) -> contentLikeDao.findLikedContentIds(conn, uid, ids)),
                () -> loadUserLikeIds(userId, "内容",
                        (conn, uid) -> contentLikeDao.findLikedContentIdsByUser(conn, uid)),
                ttlSeconds());
    }

    /**
     * 批量查询用户对多个评论的点赞状态（T4 反转：逻辑同内容批量，set 换
     * {@code user:commentLikeSet:{userId}}）；Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，不写回）。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return setCache.batchIsMember(CacheKeys.userCommentLikeSet(userId), commentIds,
                missedIds -> queryLikedIds(userId, missedIds, "评论",
                        (conn, uid, ids) -> commentLikeDao.findLikedCommentIds(conn, uid, ids)),
                () -> loadUserLikeIds(userId, "评论",
                        (conn, uid) -> commentLikeDao.findLikedCommentIdsByUser(conn, uid)),
                ttlSeconds());
    }

    // ==================== 失效（内容/评论删除级联，4.5 业务显式失效；T4 反转为仅计数失效） ====================

    /**
     * 删除内容后失效其点赞计数缓存。
     * 调用方：LikeService.deleteContentLike（ContentService 内容删除/下架级联，T6 自 ContentCacheManager 迁入）。
     * T4 反转：成员 key 已为用户维度（{@code user:likeSet}），内容删除无法廉价反查点赞者逐一 SREM，
     * 故仅失效 {@code content:likeCount:{id}}；残留成员指向已删除内容，**不清理亦无害**——
     * ① 物理删除：DB 点赞行随 {@code ContentLikeDao.deleteByContentId} 一并物理删除，残留成员与 DB
     * 不一致，但内容 id 自增不复用、UI 无对已删除内容的点赞状态查询路径，永不外显（不可恢复、无
     * 读自愈路径触发）；② 下架隐藏（3.10 软删）：点赞记录保留 DB，残留成员与 DB 真理一致，恢复后
     * 读自愈对齐。两场景均无一致性破坏。
     */
    public void deleteContentLike(long contentId) {
        cacheAside.invalidate(CacheKeys.contentLikeCount(contentId));
    }

    /**
     * 删除评论后失效其点赞计数缓存（T4 反转：仅 {@code comment:likeCount:{id}}，理由同内容删除）。
     */
    public void deleteCommentLike(long commentId) {
        cacheAside.invalidate(CacheKeys.commentLikeCount(commentId));
    }

    // ==================== 内部：DB 装载（loader 样板参数化；异常抛 ServerException 属真实失败） ====================

    /** 用户维度成员 loader：内容/评论共用一个实现（label 仅用于日志；用户可见异常文案与 T2 前逐字一致）。 */
    private Set<Long> loadUserLikeIds(long userId, String label, DaoQuery<Set<Long>> query) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return query.apply(conn, userId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, label + "点赞记录 DB 查询失败, userId=" + userId, e);
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