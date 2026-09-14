package com.itheima.like.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.like.dao.CommentLikeDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

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
 * 点赞缓存服务（C 周期 T4 重制）：计数/成员分离（NEEDS 4.6）+ 统一单飞（4.9）+ 写失败=DEL 降级（4.2）。
 *
 * <p>与内容/评论缓存同构（T2/T3 惯例）：缓存类拥有 DAO + TransactionTemplate，内部完成
 * "缓存优先 → miss 单飞回填 → Redis 异常降级 DB"，业务 Service 读路径只做委托。
 *
 * <p>降级不放量（三期 T2）：Redis 异常的降级读亦经 {@link SingleFlight} 全量装载作答、
 * 不写回（D4）——同 key 并发读只打一次 DB；失败不以数据形式共享（条目移除，下一请求重试）。
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

    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final SingleFlight singleFlight;
    private final CacheAside cacheAside;
    private final CacheStats stats;

    @InjectConstructor
    public LikeCacheService(ContentLikeDao contentLikeDao, CommentLikeDao commentLikeDao,
                            TransactionTemplate transactionTemplate, RedisAccess redis,
                            SingleFlight singleFlight, CacheAside cacheAside, CacheStats stats) {
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.singleFlight = singleFlight;
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

    // ==================== 内容点赞成员（三态 + 单飞 + 降级） ====================

    /**
     * 查询用户是否点赞了某内容（三态）：
     * hit-empty（空标记）→ false；hit-data（set 存在）→ SISMEMBER；miss → 单飞回填后判成员；
     * Redis 异常 → 降级 DB（三期 T2：经单飞全量装载作答，同 key 并发只打一次 DB；不写回，4.2 读降级）。
     *
     * @param userId 用户
     * @param contentId 内容
     * @return 是否已点赞（数据库为最终答案，永不抛缓存异常）
     */
    public boolean isContentLiked(long userId, long contentId) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        try {
            List<Boolean> scan = scanLikeSet(setKey, String.valueOf(userId));
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return false; // 空标记：已确认无点赞者
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                return Boolean.TRUE.equals(scan.get(2)); // set 存在：成员判定
            }
            // miss：单飞回填（负载 = DB 全量点赞者 → 写 set 或空标记）
            stats.record(CacheStats.Event.MISS, setKey);
            Set<Long> likers = singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                Set<Long> loaded = loadContentLikers(contentId);
                writeContentLikers(contentId, loaded);
                return loaded;
            });
            return likers.contains(userId);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞状态缓存读失败，降级 DB, contentId=" + contentId, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：降级经单飞全量装载作答（与 miss 回填同 key 同 loader）——
            // 同 key 并发读只打一次 DB；仅装载不写回（D4）
            return loadLikersViaSingleFlight(setKey, () -> loadContentLikers(contentId))
                    .contains(userId);
        }
    }

    // ==================== 内容点赞计数（CacheAside：miss 单飞回填 / Redis 异常自动降级 loader） ====================

    /**
     * 查询内容点赞数（计数/成员分离：只走 count key，不加载全量成员）。
     * 0 是合法 hit-data；Redis 异常由 CacheAside 降级走 DB COUNT。
     */
    public int getContentLikeCount(long contentId) {
        Integer cached = cacheAside.get(CacheKeys.contentLikeCount(contentId), Integer.class,
                () -> loadContentLikeCount(contentId), ttlSeconds());
        // count key 从不写空标记、loader 恒返回 int（0 为合法数据），正常流程直接命中；
        // 防御分支仅兜底"hit-empty 时 get 返回 null"的 NPE 可能，不会造成重复 DB 查询
        if (cached != null) {
            return cached;
        }
        stats.record(CacheStats.Event.LOAD, CacheKeys.contentLikeCount(contentId)); // 兜底 DB 装载与 CacheAside 口径对齐
        return loadContentLikeCount(contentId);
    }

    // ==================== 评论点赞成员 / 计数（同内容，key 换 comment） ====================

    public boolean isCommentLiked(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        try {
            List<Boolean> scan = scanLikeSet(setKey, String.valueOf(userId));
            if (Boolean.TRUE.equals(scan.get(0))) {
                stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                return false;
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                stats.record(CacheStats.Event.HIT_DATA, setKey);
                return Boolean.TRUE.equals(scan.get(2));
            }
            stats.record(CacheStats.Event.MISS, setKey);
            Set<Long> likers = singleFlight.get(setKey, () -> {
                stats.record(CacheStats.Event.LOAD, setKey);
                Set<Long> loaded = loadCommentLikers(commentId);
                writeCommentLikers(commentId, loaded);
                return loaded;
            });
            return likers.contains(userId);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞状态缓存读失败，降级 DB, commentId=" + commentId, e);
            stats.record(CacheStats.Event.DEGRADE, setKey);
            // 三期 T2 降级不放量：同 isContentLiked——降级经单飞全量装载作答，不写回（D4）
            return loadLikersViaSingleFlight(setKey, () -> loadCommentLikers(commentId))
                    .contains(userId);
        }
    }

    public int getCommentLikeCount(long commentId) {
        Integer cached = cacheAside.get(CacheKeys.commentLikeCount(commentId), Integer.class,
                () -> loadCommentLikeCount(commentId), ttlSeconds());
        // 同 getContentLikeCount：count key 从不写空标记，防御分支仅兜底 NPE
        if (cached != null) {
            return cached;
        }
        stats.record(CacheStats.Event.LOAD, CacheKeys.commentLikeCount(commentId)); // 兜底 DB 装载与 CacheAside 口径对齐
        return loadCommentLikeCount(commentId);
    }

    // ==================== 批量点赞状态（pipeline + DB 兜底 + 逐个单飞回填） ====================

    /**
     * 批量查询用户对多个内容的点赞状态（一次 pipeline 扫描 + DB 批量兜底）；
     * Redis 异常 → 降级 DB（三期 T2：逐 cid 单飞全量装载作答，同 key 并发只打一次 DB；不写回）。
     *
     * @return 完整 contentId → isLiked 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
        boolean degraded = false;
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                List<Long> ids = new ArrayList<>(contentIds);
                List<Response<Boolean>> empties = new ArrayList<>();
                List<Response<Boolean>> existsList = new ArrayList<>();
                List<Response<Boolean>> members = new ArrayList<>();
                for (Long cid : ids) {
                    String setKey = CacheKeys.contentLikeSet(cid);
                    empties.add(p.exists(CacheKeys.empty(setKey)));
                    existsList.add(p.exists(setKey));
                    members.add(p.sismember(setKey, String.valueOf(userId)));
                    // T9 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）
                    p.expire(setKey, ttlSeconds());
                }
                p.sync();
                for (int i = 0; i < ids.size(); i++) {
                    String setKey = CacheKeys.contentLikeSet(ids.get(i));
                    if (Boolean.TRUE.equals(empties.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                        result.put(ids.get(i), false);
                    } else if (Boolean.TRUE.equals(existsList.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_DATA, setKey);
                        result.put(ids.get(i), members.get(i).get());
                    } else {
                        stats.record(CacheStats.Event.MISS, setKey);
                        missed.add(ids.get(i));
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量内容点赞状态缓存读失败，全部走 DB, userId=" + userId, e);
            for (Long cid : contentIds) {
                stats.record(CacheStats.Event.DEGRADE, CacheKeys.contentLikeSet(cid));
            }
            degraded = true;
            missed.addAll(contentIds);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                degradeBatchContentLikers(userId, missed, result);
            } else {
                backfillBatchContentLikers(userId, missed, result);
            }
        }
        return result;
    }

    /**
     * 批量查询用户对多个评论的点赞状态（逻辑同内容批量）；
     * Redis 异常 → 降级 DB（三期 T2：逐 cid 单飞全量装载作答，不写回）。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
        boolean degraded = false;
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                List<Long> ids = new ArrayList<>(commentIds);
                List<Response<Boolean>> empties = new ArrayList<>();
                List<Response<Boolean>> existsList = new ArrayList<>();
                List<Response<Boolean>> members = new ArrayList<>();
                for (Long cid : ids) {
                    String setKey = CacheKeys.commentLikeSet(cid);
                    empties.add(p.exists(CacheKeys.empty(setKey)));
                    existsList.add(p.exists(setKey));
                    members.add(p.sismember(setKey, String.valueOf(userId)));
                    // T9 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）
                    p.expire(setKey, ttlSeconds());
                }
                p.sync();
                for (int i = 0; i < ids.size(); i++) {
                    String setKey = CacheKeys.commentLikeSet(ids.get(i));
                    if (Boolean.TRUE.equals(empties.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_EMPTY, setKey);
                        result.put(ids.get(i), false);
                    } else if (Boolean.TRUE.equals(existsList.get(i).get())) {
                        stats.record(CacheStats.Event.HIT_DATA, setKey);
                        result.put(ids.get(i), members.get(i).get());
                    } else {
                        stats.record(CacheStats.Event.MISS, setKey);
                        missed.add(ids.get(i));
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量评论点赞状态缓存读失败，全部走 DB, userId=" + userId, e);
            for (Long cid : commentIds) {
                stats.record(CacheStats.Event.DEGRADE, CacheKeys.commentLikeSet(cid));
            }
            degraded = true;
            missed.addAll(commentIds);
        }
        if (!missed.isEmpty()) {
            if (degraded) {
                degradeBatchCommentLikers(userId, missed, result);
            } else {
                backfillBatchCommentLikers(userId, missed, result);
            }
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

    // ==================== 内部：DB 装载（loader，异常抛 ServerException 属真实失败） ====================

    private Set<Long> loadContentLikers(long contentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return contentLikeDao.findLikerIdsByContentId(conn, contentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "内容点赞者 DB 查询失败, contentId=" + contentId, e);
                    throw new ServerException("服务器异常，查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞状态失败", e);
        }
    }

    private Set<Long> loadCommentLikers(long commentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return commentLikeDao.findLikerIdsByCommentId(conn, commentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "评论点赞者 DB 查询失败, commentId=" + commentId, e);
                    throw new ServerException("服务器异常，查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞状态失败", e);
        }
    }

    private int loadContentLikeCount(long contentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return contentLikeDao.countByContentId(conn, contentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "内容点赞数 DB 查询失败, contentId=" + contentId, e);
                    throw new ServerException("服务器异常，查询点赞数失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞数失败", e);
        }
    }

    private int loadCommentLikeCount(long commentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return commentLikeDao.countByCommentId(conn, commentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "评论点赞数 DB 查询失败, commentId=" + commentId, e);
                    throw new ServerException("服务器异常，查询点赞数失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞数失败", e);
        }
    }

    // ==================== 内部：回填写入（单飞 loader 内调用；空集 → 空标记替代占位符 H11） ====================

    /** 内容点赞成员回填：非空 → SADD-union + EXPIRE（不 DEL，避免丢失并发 SADD）；空集 → 空标记。 */
    private void writeContentLikers(long contentId, Set<Long> likers) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        if (likers == null || likers.isEmpty()) {
            cacheAside.markEmpty(setKey);
            return;
        }
        try {
            redis.executeVoid(j -> {
                String[] members = likers.stream().map(String::valueOf).toArray(String[]::new);
                j.sadd(setKey, members);
                j.expire(setKey, ttlSeconds());
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞成员回填失败，读自愈, contentId=" + contentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
        }
    }

    private void writeCommentLikers(long commentId, Set<Long> likers) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        if (likers == null || likers.isEmpty()) {
            cacheAside.markEmpty(setKey);
            return;
        }
        try {
            redis.executeVoid(j -> {
                String[] members = likers.stream().map(String::valueOf).toArray(String[]::new);
                j.sadd(setKey, members);
                j.expire(setKey, ttlSeconds());
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞成员回填失败，读自愈, commentId=" + commentId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, setKey);
        }
    }

    /** 批量内容回填：DB 批量查"命中关系" + 逐个单飞回填全量成员（防并发惊群）。 */
    private void backfillBatchContentLikers(long userId, List<Long> missed, Map<Long, Boolean> result) {
        Set<Long> likedSet = transactionTemplate.execute(conn -> {
            try {
                return contentLikeDao.findLikedContentIds(conn, userId, missed);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "批量内容点赞状态 DB 查询失败, userId=" + userId, e);
                throw new ServerException("服务器异常，批量查询点赞状态失败");
            }
        });
        for (Long cid : missed) {
            result.put(cid, likedSet.contains(cid));
        }
        for (Long cid : missed) {
            singleFlight.get(CacheKeys.contentLikeSet(cid), () -> {
                stats.record(CacheStats.Event.LOAD, CacheKeys.contentLikeSet(cid));
                Set<Long> likers = loadContentLikers(cid);
                writeContentLikers(cid, likers);
                return null;
            });
        }
    }

    private void backfillBatchCommentLikers(long userId, List<Long> missed, Map<Long, Boolean> result) {
        Set<Long> likedSet = transactionTemplate.execute(conn -> {
            try {
                return commentLikeDao.findLikedCommentIds(conn, userId, missed);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "批量评论点赞状态 DB 查询失败, userId=" + userId, e);
                throw new ServerException("服务器异常，批量查询点赞状态失败");
            }
        });
        for (Long cid : missed) {
            result.put(cid, likedSet.contains(cid));
        }
        for (Long cid : missed) {
            singleFlight.get(CacheKeys.commentLikeSet(cid), () -> {
                stats.record(CacheStats.Event.LOAD, CacheKeys.commentLikeSet(cid));
                Set<Long> likers = loadCommentLikers(cid);
                writeCommentLikers(cid, likers);
                return null;
            });
        }
    }

    // ==================== 降级（三期 T2：Redis 异常态，单飞全量装载作答、不写回） ====================

    /**
     * 三期 T2 降级装载（唯一入口，防多处漂移）：经单飞全量装载成员——
     * 同 key 并发只打一次 DB；仅装载不写回（D4）；LOAD 由 leader 记一次。
     */
    private Set<Long> loadLikersViaSingleFlight(String setKey, java.util.function.Supplier<Set<Long>> loader) {
        return singleFlight.get(setKey, () -> {
            stats.record(CacheStats.Event.LOAD, setKey);
            return loader.get();
        });
    }

    /**
     * 三期 T2 降级路径：逐 cid 经单飞全量装载成员后作答（仅装载不写回，D4）——
     * 同 contentLikeSet key 的并发批量读只打一次 DB。
     */
    private void degradeBatchContentLikers(long userId, List<Long> missed, Map<Long, Boolean> result) {
        for (Long cid : missed) {
            Set<Long> likers = loadLikersViaSingleFlight(CacheKeys.contentLikeSet(cid),
                    () -> loadContentLikers(cid));
            result.put(cid, likers.contains(userId));
        }
    }

    /** 同 {@link #degradeBatchContentLikers}，评论域（commentLikeSet key）。 */
    private void degradeBatchCommentLikers(long userId, List<Long> missed, Map<Long, Boolean> result) {
        for (Long cid : missed) {
            Set<Long> likers = loadLikersViaSingleFlight(CacheKeys.commentLikeSet(cid),
                    () -> loadCommentLikers(cid));
            result.put(cid, likers.contains(userId));
        }
    }

    /** 单条成员三态扫描：一趟 pipeline 返回 [空标记, set 存在, 是否成员]（元素可能为 null，用 asList）。
     *  T9 滑动续期：命中 set 顺带续期（无条件入列，set 不存在返回 0 无效果；空标记不续）。 */
    private List<Boolean> scanLikeSet(String setKey, String userIdStr) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
            Response<Boolean> exists = p.exists(setKey);
            Response<Boolean> member = p.sismember(setKey, userIdStr);
            p.expire(setKey, ttlSeconds());
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), member.get());
        });
    }

    private long ttlSeconds() {
        return AppConfig.getLikeTtlSeconds();
    }
}