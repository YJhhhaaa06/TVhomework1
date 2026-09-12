package com.itheima.like.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
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
 * <p>写路径（LikeService DB 事务提交后调用）：条件写——仅当 key 已存在才 INCR/DECR/SADD/SREM，
 * 冷 key 不创建残缺缓存（防"半套成员/计数"被命中；交给读回填 DB 真理）；any 失败 → 失效 key
 * 让读自愈（4.2），不抛出。计数/成员/空标记全部可降级，Redis 挂不导致点赞接口 500（H5）。
 */
@Component
public class LikeCacheService {

    private static final Logger LOGGER = LogUtil.getLogger(LikeCacheService.class);

    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final SingleFlight singleFlight;
    private final CacheAside cacheAside;

    @InjectConstructor
    public LikeCacheService(ContentLikeDao contentLikeDao, CommentLikeDao commentLikeDao,
                            TransactionTemplate transactionTemplate, RedisAccess redis,
                            SingleFlight singleFlight, CacheAside cacheAside) {
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.singleFlight = singleFlight;
        this.cacheAside = cacheAside;
    }

    // ==================== 内容点赞 / 取消（写路径，DB 提交后调用，4.2/4.6） ====================

    /**
     * 缓存：用户给内容点赞。
     * 清残留空标记 + 条件写（set 存在才 SADD，count 存在才 INCR）；
     * 失败 → 失效 count+set 让读自愈，不抛出。
     */
    public void likeContent(long userId, long contentId) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> {
                // 第一趟：清空标记 + 探测两个 key 是否存在
                Pipeline p = j.pipelined();
                p.del(CacheKeys.empty(setKey));
                Response<Boolean> setExists = p.exists(setKey);
                Response<Boolean> countExists = p.exists(countKey);
                p.sync();
                // 第二趟：条件写（key 已存在才增值/加成员，避免冷 key 创建残缺缓存）
                if (Boolean.TRUE.equals(setExists.get())) {
                    j.sadd(setKey, String.valueOf(userId));
                }
                if (Boolean.TRUE.equals(countExists.get())) {
                    j.incr(countKey);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    /**
     * 缓存：用户取消内容点赞。
     * 条件写（set 存在才 SREM，count 存在才 DECR）；失败 → 失效 count+set，不抛出。
     */
    public void unlikeContent(long userId, long contentId) {
        String setKey = CacheKeys.contentLikeSet(contentId);
        String countKey = CacheKeys.contentLikeCount(contentId);
        try {
            redis.executeVoid(j -> {
                if (j.exists(setKey)) {
                    j.srem(setKey, String.valueOf(userId));
                }
                if (j.exists(countKey)) {
                    j.decr(countKey);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消内容点赞缓存写失败，失效 key 让读自愈, contentId=" + contentId, e);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    // ==================== 评论点赞 / 取消（同上） ====================

    public void likeComment(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> {
                Pipeline p = j.pipelined();
                p.del(CacheKeys.empty(setKey));
                Response<Boolean> setExists = p.exists(setKey);
                Response<Boolean> countExists = p.exists(countKey);
                p.sync();
                if (Boolean.TRUE.equals(setExists.get())) {
                    j.sadd(setKey, String.valueOf(userId));
                }
                if (Boolean.TRUE.equals(countExists.get())) {
                    j.incr(countKey);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    public void unlikeComment(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        String countKey = CacheKeys.commentLikeCount(commentId);
        try {
            redis.executeVoid(j -> {
                if (j.exists(setKey)) {
                    j.srem(setKey, String.valueOf(userId));
                }
                if (j.exists(countKey)) {
                    j.decr(countKey);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "取消评论点赞缓存写失败，失效 key 让读自愈, commentId=" + commentId, e);
            cacheAside.invalidate(countKey, setKey);
        }
    }

    // ==================== 内容点赞成员（三态 + 单飞 + 降级） ====================

    /**
     * 查询用户是否点赞了某内容（三态）：
     * hit-empty（空标记）→ false；hit-data（set 存在）→ SISMEMBER；miss → 单飞回填后判成员；
     * Redis 异常 → 降级 DB 单行查询，不写回（4.2 读降级）。
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
                return false; // 空标记：已确认无点赞者
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                return Boolean.TRUE.equals(scan.get(2)); // set 存在：成员判定
            }
            // miss：单飞回填（负载 = DB 全量点赞者 → 写 set 或空标记）
            Set<Long> likers = singleFlight.get(setKey, () -> {
                Set<Long> loaded = loadContentLikers(contentId);
                writeContentLikers(contentId, loaded);
                return loaded;
            });
            return likers.contains(userId);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容点赞状态缓存读失败，降级 DB, contentId=" + contentId, e);
            return isContentLikedFromDb(userId, contentId);
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
        return cached != null ? cached : loadContentLikeCount(contentId);
    }

    // ==================== 评论点赞成员 / 计数（同内容，key 换 comment） ====================

    public boolean isCommentLiked(long userId, long commentId) {
        String setKey = CacheKeys.commentLikeSet(commentId);
        try {
            List<Boolean> scan = scanLikeSet(setKey, String.valueOf(userId));
            if (Boolean.TRUE.equals(scan.get(0))) {
                return false;
            }
            if (Boolean.TRUE.equals(scan.get(1))) {
                return Boolean.TRUE.equals(scan.get(2));
            }
            Set<Long> likers = singleFlight.get(setKey, () -> {
                Set<Long> loaded = loadCommentLikers(commentId);
                writeCommentLikers(commentId, loaded);
                return loaded;
            });
            return likers.contains(userId);
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论点赞状态缓存读失败，降级 DB, commentId=" + commentId, e);
            return isCommentLikedFromDb(userId, commentId);
        }
    }

    public int getCommentLikeCount(long commentId) {
        Integer cached = cacheAside.get(CacheKeys.commentLikeCount(commentId), Integer.class,
                () -> loadCommentLikeCount(commentId), ttlSeconds());
        // 同 getContentLikeCount：count key 从不写空标记，防御分支仅兜底 NPE
        return cached != null ? cached : loadCommentLikeCount(commentId);
    }

    // ==================== 批量点赞状态（pipeline + DB 兜底 + 逐个单飞回填） ====================

    /**
     * 批量查询用户对多个内容的点赞状态（一次 pipeline 扫描 + DB 批量兜底）。
     *
     * @return 完整 contentId → isLiked 映射（含 DB 兜底结果，无缺失）
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
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
                }
                p.sync();
                for (int i = 0; i < ids.size(); i++) {
                    if (Boolean.TRUE.equals(empties.get(i).get())) {
                        result.put(ids.get(i), false);
                    } else if (Boolean.TRUE.equals(existsList.get(i).get())) {
                        result.put(ids.get(i), members.get(i).get());
                    } else {
                        missed.add(ids.get(i));
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量内容点赞状态缓存读失败，全部走 DB, userId=" + userId, e);
            missed.addAll(contentIds);
        }
        if (!missed.isEmpty()) {
            backfillBatchContentLikers(userId, missed, result);
        }
        return result;
    }

    /**
     * 批量查询用户对多个评论的点赞状态（逻辑同内容批量）。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> missed = new ArrayList<>();
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
                }
                p.sync();
                for (int i = 0; i < ids.size(); i++) {
                    if (Boolean.TRUE.equals(empties.get(i).get())) {
                        result.put(ids.get(i), false);
                    } else if (Boolean.TRUE.equals(existsList.get(i).get())) {
                        result.put(ids.get(i), members.get(i).get());
                    } else {
                        missed.add(ids.get(i));
                    }
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "批量评论点赞状态缓存读失败，全部走 DB, userId=" + userId, e);
            missed.addAll(commentIds);
        }
        if (!missed.isEmpty()) {
            backfillBatchCommentLikers(userId, missed, result);
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

    /** Redis 挂时降级：DB 单行 isLiked 查询（不写回，下次 miss 自愈）。 */
    private boolean isContentLikedFromDb(long userId, long contentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return contentLikeDao.isLiked(conn, userId, contentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "内容点赞状态 DB 查询失败, userId=" + userId + ", contentId=" + contentId, e);
                    throw new ServerException("服务器异常，查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞状态失败", e);
        }
    }

    private boolean isCommentLikedFromDb(long userId, long commentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return commentLikeDao.isLiked(conn, userId, commentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "评论点赞状态 DB 查询失败, userId=" + userId + ", commentId=" + commentId, e);
                    throw new ServerException("服务器异常，查询点赞状态失败");
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("服务器异常，查询点赞状态失败", e);
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
                Set<Long> likers = loadCommentLikers(cid);
                writeCommentLikers(cid, likers);
                return null;
            });
        }
    }

    /** 单条成员三态扫描：一趟 pipeline 返回 [空标记, set 存在, 是否成员]（元素可能为 null，用 asList）。 */
    private List<Boolean> scanLikeSet(String setKey, String userIdStr) {
        return redis.execute(j -> {
            Pipeline p = j.pipelined();
            Response<Boolean> empty = p.exists(CacheKeys.empty(setKey));
            Response<Boolean> exists = p.exists(setKey);
            Response<Boolean> member = p.sismember(setKey, userIdStr);
            p.sync();
            return java.util.Arrays.asList(empty.get(), exists.get(), member.get());
        });
    }

    private long ttlSeconds() {
        return AppConfig.getLikeTtlSeconds();
    }
}