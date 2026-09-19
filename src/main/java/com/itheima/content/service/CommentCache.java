package com.itheima.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.JacksonCodec;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.comment.dao.CommentDao;
import com.itheima.config.AppConfig;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Pipeline;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 评论缓存（T10-A 重构：整树单 key → 两键组 + 主楼窗口装载）。统一 Redis 缓存层。
 *
 * <p><b>T10-A 结构（替换旧 {@code content:comments:{id}} 整树单 key）</b>：
 * <ul>
 *   <li>{@code content:comments:{id}:roots}（List）：主楼序列，元素=主楼 CommentCacheDTO JSON
 *       <b>无 children</b>，comment_id 升序；LRANGE 窗口读 + RPUSH 尾追加（窗口装载）。</li>
 *   <li>{@code content:comments:{id}:replies}（Hash）：field=主楼 id，值=该主楼 children 全量 JSON；
 *       HMGET 只拉该页主楼（页成本 ∝ 该页，与评论总量弱相关——治 U-20 根因）。</li>
 *   <li>{@code content:comments:{id}:count}（String int）：真实主楼条数，首装时惰性 COUNT 一次。</li>
 * </ul>
 *
 * <p><b>窗口装载（不一次性查全库）</b>：主楼 List 水位不足时 DB keyset 查询
 * （{@code comment_id > lastId LIMIT}）追加；楼中楼 field 缺失时按主楼懒载
 * （{@code parent_id IN (missingIds)} 一次取）。冷门老视频只装被看的部分。
 *
 * <p><b>三态/降级语义（对齐 T3 既有契约）</b>：
 * <ul>
 *   <li>roots 空标记（{@code empty:rootsKey}，60s）= 已确认无评论；命中 → 空，不查 DB；</li>
 *   <li>loader 抛 {@link DatabaseException}（DB 加载失败）→ <b>不写空标记、不 DEL 旧 key</b>，
 *       本次读转空（对外行为不变，不固化瞬时故障）；</li>
 *   <li>Redis 异常 → 降级直接 DB（窗口/全量按调用形态），不写回（D4）。</li>
 * </ul>
 *
 * <p><b>失效重映射（DB 为源真理 + 失效自愈哲学不变，前端业务失效点见 CommentService）</b>：
 * 增/删主楼 → 失效 roots+count（读懒重建窗口，量=被看窗口）；回复增删、点赞 → 定向 HDEL 该主楼
 * replies field（懒载刷新 like_count / children）。
 *
 * <p>缺省全量数组路径（不传分页参数）= 从两键组全量拼装等价整树（缺省语义即全量；
 * T10-B 前端改传参后自然缓解全量装载）。
 */
@Component
public class CommentCache {

    private static final Logger LOGGER = LogUtil.getLogger(CommentCache.class);

    /** 窗口装载单趟上限（每次 DB keyset 装载条数；并发水位按需轮次推进，防单趟过大）。 */
    private static final int LOAD_BATCH_LIMIT = 200;

    private static final TypeReference<List<CommentCacheDTO>> COMMENT_LIST_TYPE = new TypeReference<>() {
    };

    private final CommentDao commentDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redisAccess;
    private final SingleFlight singleFlight;
    private final JacksonCodec codec;
    private final CacheStats stats;

    @InjectConstructor
    public CommentCache(CommentDao commentDao, TransactionTemplate transactionTemplate,
                        RedisAccess redisAccess, SingleFlight singleFlight,
                        JacksonCodec codec, CacheStats stats) {
        this.commentDao = commentDao;
        this.transactionTemplate = transactionTemplate;
        this.redisAccess = redisAccess;
        this.singleFlight = singleFlight;
        this.codec = codec;
        this.stats = stats;
    }

    // ==================== 读路径（T10-A：两键组 + 窗口装载） ====================

    /**
     * 分页窗口读（T10-A 主入口）：返回该页主楼树（children 全量随行，T8 契约保持）+ 真实主楼总数。
     *
     * <p>命中路径 = 主楼 LRANGE 窗口 + 楼中楼 HMGET 该页（单次成本 ∝ 该页）；主楼 List 水位不足时
     * DB keyset 追加装载。越界页返回空列表但 {@code rootTotal} 仍为真实主楼数（对齐 T8 sliceRoots 语义）。
     */
    public PageWindow getRootPage(long contentId, int page, int pageSize) {
        // 兜底非法分页参数（Controller 已归一：page≥1、pageSize 1~50）——非正参数给空页而不是
        // 假装第 1 页，避免 subList 越界；total 仍取真实主楼数（对齐 T8 sliceRoots 语义）
        if (page < 1 || pageSize < 1) {
            return new PageWindow(new ArrayList<>(), loadRootTotal(contentId));
        }
        long from = (long) (page - 1) * pageSize;
        List<CommentCacheDTO> roots = prepareRoots(contentId, contentIdPageEnd(page, pageSize));
        if (roots == null) {
            return PageWindow.empty(); // hit-empty（已确认无评论）
        }
        int total = loadRootTotal(contentId); // count key（缺 → DB 兜底）
        List<CommentCacheDTO> pageRoots = sliceRoots(roots, from, pageSize);
        return new PageWindow(attachReplies(contentId, pageRoots), total);
    }

    /**
     * 缺省全量整树（不传分页参数的数组语义）：从两键组拼装等价整树（全量装载，语义即全量）。
     *
     * @return 整树主楼列表（children 随行）；null = 已确认无评论
     */
    public List<CommentCacheDTO> getFullTree(long contentId) {
        List<CommentCacheDTO> roots = prepareRoots(contentId, Long.MAX_VALUE);
        if (roots == null) {
            return null;
        }
        return attachReplies(contentId, roots);
    }

    /**
     * 主楼序列就绪（水位装载 + 全量读取）：返回已装主楼列表（无 children），null = 已确认无评论。
     *
     * <p>Redis 读异常 → 降级：直接 DB keyset 取到目标窗口（不写回，D4）；
     * DB 装载失败 → 记日志转空（对外行为不变）。
     */
    private List<CommentCacheDTO> prepareRoots(long contentId, long needWatermark) {
        String rootsKey = CacheKeys.contentCommentRoots(contentId);
        try {
            if (Boolean.TRUE.equals(redisAccess.execute(j -> j.exists(CacheKeys.empty(rootsKey))))) {
                stats.record(CacheStats.Event.HIT_EMPTY, rootsKey);
                return null;
            }
            try {
                ensureRootsWindow(rootsKey, contentId, needWatermark);
            } catch (RuntimeException e) {
                // 装载失败（DB 瞬时故障等）→ 不使用错误结果，本次降级走 DB
                LOGGER.log(Level.WARNING, "评论主楼窗口装载失败（本次降级走 DB）, contentId=" + contentId, e);
            }
            List<String> jsons = redisAccess.execute(j -> {
                List<String> values = j.lrange(rootsKey, 0, -1);
                j.expire(rootsKey, ttlSeconds()); // 命中续期（对齐旧 CacheAside 滑动续期）
                return values;
            });
            List<CommentCacheDTO> roots = new ArrayList<>(jsons.size());
            for (String json : jsons) {
                if (json != null && !json.isEmpty()) {
                    roots.add(codec.fromJson(json, CommentCacheDTO.class));
                }
            }
            stats.record(CacheStats.Event.HIT_DATA, rootsKey);
            // 空列表 = 已确认无评论（hit-empty / DB 无主楼），对齐旧 getCommentTree 的 null 语义
            return roots.isEmpty() ? null : roots;
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论主楼缓存读异常（降级走 DB）, contentId=" + contentId, e);
            stats.record(CacheStats.Event.DEGRADE, rootsKey);
            return loadRootsFromDb(contentId, needWatermark, false);
        }
    }

    /**
     * 主楼 List 窗口装载：水位不足时按 keyset 轮次从 DB 追加（每轮单飞，双检后装载）。
     *
     * <p>首装（水位=0）时同时写 count key（真实主楼总数）；DB 无主楼 → 写 roots 空标记。
     * 单飞 key 按"水位轮次"区分 — 同轮并发只装载一次，串行请求复用已装水位自然推进。
     */
    private void ensureRootsWindow(String rootsKey, long contentId, long needWatermark) {
        if (needWatermark <= 0) {
            return;
        }
        while (true) {
            long watermark = redisAccess.execute(j -> j.llen(rootsKey));
            if (watermark >= needWatermark) {
                return;
            }
            long round = watermark;
            boolean reachedTail;
            try {
                reachedTail = singleFlight.get(rootsKey + ":load:" + round, () -> {
                    long len2 = redisAccess.execute(j -> j.llen(rootsKey));
                    if (len2 >= needWatermark) {
                        return true; // 已被并发装载补足
                    }
                    long afterId = len2 > 0 ? lastRootId(rootsKey, len2) : 0L;
                    long batch = Math.min(LOAD_BATCH_LIMIT, needWatermark - len2);
                    RootLoad loaded = transactionTemplate.execute(conn -> {
                        try {
                            List<CommentCacheDTO> mains =
                                    commentDao.getMainCommentsAfter(conn, contentId, afterId, (int) batch);
                            int total = (len2 == 0) ? commentDao.countMainComments(conn, contentId) : -1;
                            return new RootLoad(mains, total);
                        } catch (SQLException e) {
                            throw new DatabaseException("评论主楼窗口查询失败", e);
                        }
                    });
                    if (loaded.mains.isEmpty()) {
                        if (len2 == 0) {
                            markEmpty(rootsKey); // 首装无主楼 → 空标记（防穿透）
                        }
                        return true; // DB 尾部无更多
                    }
                    writeRootsAppend(rootsKey, contentId, len2, loaded);
                    return false;
                });
            } catch (RuntimeException e) {
                throw e; // DB 装载失败 → 上层降级
            }
            if (reachedTail) {
                return;
            }
        }
    }

    /** 追加装载结果到主楼 List（pipeline：清空标记[首装] + RPUSH + EXPIRE + count[首装]）。 */
    private void writeRootsAppend(String rootsKey, long contentId, long existedBefore, RootLoad loaded) {
        try {
            redisAccess.executeVoid(j -> {
                Pipeline p = j.pipelined();
                if (existedBefore == 0) {
                    p.del(CacheKeys.empty(rootsKey));
                }
                for (CommentCacheDTO main : loaded.mains) {
                    p.rpush(rootsKey, codec.toJson(main));
                }
                p.expire(rootsKey, ttlSeconds());
                if (existedBefore == 0 && loaded.total >= 0) {
                    p.setex(CacheKeys.contentCommentRootCount(contentId), ttlSeconds(),
                            String.valueOf(loaded.total));
                }
                p.sync();
            });
        } catch (CacheException e) {
            stats.record(CacheStats.Event.WRITE_FAIL, rootsKey);
            throw e; // 写失败：不掩蔽，读路径下次重试自愈
        }
    }

    /** 已装主楼列表最后一条的 comment_id（Window 读取；水位 0 时调用方不会走到）。 */
    private long lastRootId(String rootsKey, long watermark) {
        String json = redisAccess.execute(j -> j.lindex(rootsKey, watermark - 1));
        if (json == null || json.isEmpty()) {
            return 0L;
        }
        return codec.fromJson(json, CommentCacheDTO.class).getCommentId();
    }

    /** 降级 DB 读取（Redis 不可用）：keyset 从 0 取到目标窗口（needWatermark=MAX → 全量语义循环装完）。不写回（D4）。 */
    private List<CommentCacheDTO> loadRootsFromDb(long contentId, long needWatermark, boolean fullCount) {
        try {
            if (needWatermark == Long.MAX_VALUE) {
                // 缺省全量数组的降级语义 = **全量**（与缓存路径一致，不能截断前 N 条）
                List<CommentCacheDTO> all = new ArrayList<>();
                long afterId = 0L;
                while (true) {
                    long cursor = afterId; // lambda 需捕获 effectively-final
                    List<CommentCacheDTO> batch = transactionTemplate.execute(conn -> {
                        try {
                            return commentDao.getMainCommentsAfter(conn, contentId, cursor, LOAD_BATCH_LIMIT);
                        } catch (SQLException e) {
                            throw new DatabaseException("评论主楼降级查询失败", e);
                        }
                    });
                    if (batch == null || batch.isEmpty()) {
                        return all;
                    }
                    all.addAll(batch);
                    afterId = batch.get(batch.size() - 1).getCommentId();
                }
            }
            // 分页窗口降级：keyset 从 0 取到窗口末尾（>= 被读页），后续页由后续请求各自装载
            return transactionTemplate.execute(conn -> {
                try {
                    return commentDao.getMainCommentsAfter(conn, contentId, 0L,
                            Math.min(LOAD_BATCH_LIMIT, batchToInt(needWatermark)));
                } catch (SQLException e) {
                    throw new DatabaseException("评论主楼降级查询失败", e);
                }
            });
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "评论主楼降级查询失败（按无评论处理，对外行为不变）, contentId=" + contentId, e);
            return null;
        }
    }

    private static int batchToInt(long batch) {
        return batch >= Integer.MAX_VALUE ? LOAD_BATCH_LIMIT : (int) batch;
    }

    /** 真实主楼总数：优先 count key；缺失 → DB 即时 COUNT（低频兜底）。 */
    private int loadRootTotal(long contentId) {
        String countKey = CacheKeys.contentCommentRootCount(contentId);
        try {
            String value = redisAccess.execute(j -> j.get(countKey));
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (CacheException | NumberFormatException e) {
            LOGGER.log(Level.WARNING, "评论主楼总数读取异常（DB 兜底）, contentId=" + contentId, e);
        }
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return commentDao.countMainComments(conn, contentId);
                } catch (SQLException e) {
                    throw new DatabaseException("评论主楼总数查询失败", e);
                }
            });
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "评论主楼总数查询失败（按 0 处理）, contentId=" + contentId, e);
            return 0;
        }
    }

    /** 主楼页切片（越界页空列表；调用方信封带真实 total，与 T8 sliceRoots 语义一致）。 */
    private static List<CommentCacheDTO> sliceRoots(List<CommentCacheDTO> roots, long from, int pageSize) {
        if (from >= roots.size()) {
            return new ArrayList<>();
        }
        int to = (int) Math.min(from + pageSize, roots.size());
        return new ArrayList<>(roots.subList((int) from, to));
    }

    /** 页目标水位 = 该页末尾元素索引 + 1；防止 (page-1)*pageSize 溢出用 long。 */
    private static long contentIdPageEnd(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            return 0;
        }
        return (long) (page - 1) * pageSize + pageSize;
    }

    /**
     * 楼中楼随行（T10-A）：为页内主楼装配 children（全量随行，保持 T8 JSON 形状）。
     *
     * <p>命中 = HMGET 该页主楼 field；field 缺失（未装载主楼）→ 单飞懒载 DB 按主楼分组一次取 →
     * HSET 回填（无回复 → 空数组 []，与 T8 children 数组形状一致）；Redis 异常 → 降级 DB 直取不写回。
     */
    private List<CommentCacheDTO> attachReplies(long contentId, List<CommentCacheDTO> roots) {
        if (roots == null) {
            return null;
        }
        if (roots.isEmpty()) {
            return new ArrayList<>();
        }
        String repliesKey = CacheKeys.contentCommentReplies(contentId);
        List<Long> rootIds = new ArrayList<>(roots.size());
        for (CommentCacheDTO root : roots) {
            rootIds.add(root.getCommentId());
        }

        Map<Long, List<CommentCacheDTO>> childrenByRoot;
        try {
            childrenByRoot = redisAccess.execute(j -> {
                Map<Long, List<CommentCacheDTO>> result = new HashMap<>();
                List<Long> missing = new ArrayList<>();
                Map<Long, String> got = new HashMap<>();
                List<String> values = j.hmget(repliesKey,
                        rootIds.stream().map(String::valueOf).toArray(String[]::new));
                for (int i = 0; i < rootIds.size(); i++) {
                    String json = values.get(i);
                    if (json == null || json.isEmpty()) {
                        missing.add(rootIds.get(i));
                    } else {
                        got.put(rootIds.get(i), json);
                    }
                }
                if (!missing.isEmpty()) {
                    Map<Long, List<CommentCacheDTO>> loaded = lazilyLoadReplies(repliesKey, contentId, missing);
                    for (Map.Entry<Long, List<CommentCacheDTO>> e : loaded.entrySet()) {
                        got.put(e.getKey(), codec.toJson(e.getValue()));
                    }
                }
                for (Long rootId : rootIds) {
                    String json = got.get(rootId);
                    result.put(rootId, json == null ? new ArrayList<>()
                            : codec.fromJson(json, COMMENT_LIST_TYPE));
                }
                j.expire(repliesKey, ttlSeconds()); // 命中续期
                return result;
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论楼中楼缓存读异常（降级走 DB）, contentId=" + contentId, e);
            stats.record(CacheStats.Event.DEGRADE, repliesKey);
            childrenByRoot = loadRepliesFromDb(contentId, rootIds);
            if (childrenByRoot == null) {
                childrenByRoot = new HashMap<>();
            }
        }

        List<CommentCacheDTO> pageTree = new ArrayList<>(roots.size());
        for (CommentCacheDTO root : roots) {
            List<CommentCacheDTO> children = childrenByRoot.get(root.getCommentId());
            root.setChildren(children == null ? new ArrayList<>() : children);
            pageTree.add(root);
        }
        return pageTree;
    }

    /** 楼中楼懒载（field 缺失主楼）：单飞按主楼批量 DB 取 → HSET 回填（空组存空数组）。 */
    private Map<Long, List<CommentCacheDTO>> lazilyLoadReplies(String repliesKey, long contentId, List<Long> missing) {
        Map<Long, List<CommentCacheDTO>> grouped;
        try {
            grouped = singleFlight.get(repliesKey + ":load:" + contentId, () -> {
                try {
                    List<CommentCacheDTO> rows = transactionTemplate.execute(conn -> {
                        try {
                            return commentDao.getRepliesByRootIds(conn, contentId, missing);
                        } catch (SQLException e) {
                            throw new DatabaseException("评论楼中楼懒载查询失败", e);
                        }
                    });
                    return groupByParent(rows);
                } catch (DatabaseException e) {
                    // DB 装载失败：不写 field（保持"未装载"态，下次重试）、不写空标记
                    LOGGER.log(Level.WARNING,
                            "评论楼中楼懒载失败（保持未装载态，下次重试）, contentId=" + contentId, e);
                    return Map.of();
                }
            });
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "评论楼中楼懒载异常（保持未装载态）, contentId=" + contentId, e);
            return Map.of();
        }
        try {
            redisAccess.executeVoid(j -> {
                Pipeline p = j.pipelined();
                for (Long rootId : missing) {
                    List<CommentCacheDTO> children = grouped.getOrDefault(rootId, new ArrayList<>());
                    p.hset(repliesKey, String.valueOf(rootId), codec.toJson(children));
                }
                p.expire(repliesKey, ttlSeconds());
                p.sync();
            });
        } catch (CacheException e) {
            stats.record(CacheStats.Event.WRITE_FAIL, repliesKey);
            // 回填失败：不掩蔽，本次已拿到结果（调用方绕过缓存直接组装）；下次重试
        }
        return grouped;
    }

    /** 降级 DB：按主楼批量取楼中楼（不写回，D4）。返回按 parent_id 分组；DB 失败返回 null。 */
    private Map<Long, List<CommentCacheDTO>> loadRepliesFromDb(long contentId, List<Long> rootIds) {
        try {
            List<CommentCacheDTO> rows = transactionTemplate.execute(conn -> {
                try {
                    return commentDao.getRepliesByRootIds(conn, contentId, rootIds);
                } catch (SQLException e) {
                    throw new DatabaseException("评论楼中楼降级查询失败", e);
                }
            });
            return groupByParent(rows);
        } catch (DatabaseException e) {
            LOGGER.log(Level.WARNING, "评论楼中楼降级查询失败（按无回复处理）, contentId=" + contentId, e);
            return null;
        }
    }

    /** 扁平行按 parent_id 分组成主楼 → children（升序由 SQL ORDER BY 保证）。 */
    private static Map<Long, List<CommentCacheDTO>> groupByParent(List<CommentCacheDTO> rows) {
        Map<Long, List<CommentCacheDTO>> grouped = new HashMap<>();
        for (CommentCacheDTO c : rows) {
            Long parentId = c.getParentId() == null ? 0L : c.getParentId();
            grouped.computeIfAbsent(parentId, k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    /**
     * 展平评论树为全部评论 id（含 children），供批量评论点赞状态查询（自 ContentCacheManager 迁入）。
     */
    public List<Long> collectCommentIds(List<CommentCacheDTO> tree) {
        List<Long> ids = new ArrayList<>();
        for (CommentCacheDTO ccVO : tree) {
            collectIdsRecursive(ccVO, ids);
        }
        return ids;
    }

    // ==================== 写路径（业务显式失效，均应在 DB 事务提交后调用，4.5） ====================

    /** 评论相关整组失效（增主楼/删主楼/级联删除）：DEL roots + replies + count 及其空标记，读懒重建。 */
    public void invalidateComments(long contentId) {
        String rootsKey = CacheKeys.contentCommentRoots(contentId);
        String repliesKey = CacheKeys.contentCommentReplies(contentId);
        String countKey = CacheKeys.contentCommentRootCount(contentId);
        invalidateQuietly(rootsKey, repliesKey, countKey);
    }

    /** 主楼序列失效（增/删主楼后）：DEL roots + count（下次读懒建窗口并重 COUNT），replies 保留（其他主楼 children 未变）。 */
    public void invalidateRoots(long contentId) {
        invalidateQuietly(CacheKeys.contentCommentRoots(contentId),
                CacheKeys.contentCommentRootCount(contentId));
    }

    /** 定向失效某主楼的 replies field（回复增/删、点赞后）：HDEL field，懒载刷新。 */
    public void invalidateReplyUnder(long contentId, Long rootId) {
        if (rootId == null) {
            invalidateComments(contentId); // 无法定位主楼 → 整组失效兜底
            return;
        }
        String repliesKey = CacheKeys.contentCommentReplies(contentId);
        try {
            redisAccess.executeVoid(j -> j.hdel(repliesKey, String.valueOf(rootId)));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论 replies field 失效失败（缓存放任 TTL 自愈）, contentId=" + contentId, e);
        }
    }

    /** 评论点赞/取消后：定位所属内容 + 所属主楼，定向 HDEL 该主楼 replies field；定位失败 → 整组失效。 */
    public void notifyCommentLikeChanged(long commentId) {
        CommentRef ref = locateCommentRef(commentId);
        if (ref == null) {
            return; // 定位失败（评论已删）——缓存保持，读自愈
        }
        if (ref.rootId == null) {
            invalidateComments(ref.contentId); // 主楼上溯失败 → 整组失效兜底
            return;
        }
        invalidateReplyUnder(ref.contentId, ref.rootId);
    }

    // ==================== 内部 ====================

    /** 定位评论所属内容与所属主楼 id（点赞失效路径：一条轻查询组定位 rootId）。 */
    private CommentRef locateCommentRef(long commentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    Long contentId = commentDao.getContentIdByCommentId(conn, commentId);
                    if (contentId == null) {
                        return null;
                    }
                    Long rootId = commentDao.getRootIdByCommentId(conn, commentId);
                    return new CommentRef(contentId, rootId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "评论所属内容/主楼查询失败, commentId=" + commentId, e);
                    return null;
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "评论所属内容/主楼查询异常, commentId=" + commentId, e);
            return null;
        }
    }

    /** 空标记（对齐 CacheAside.markEmpty 语义）：数据 key 已存在（并发回填）时跳过，防固化假空。 */
    private void markEmpty(String rootsKey) {
        try {
            redisAccess.executeVoid(j -> {
                if (!j.exists(rootsKey)) {
                    j.setex(CacheKeys.empty(rootsKey), CacheKeys.EMPTY_MARKER_TTL_SECONDS,
                            CacheKeys.EMPTY_MARKER_VALUE);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "评论空标记写入失败, rootsKey=" + rootsKey, e);
        }
    }

    /** 批量静默失效（数据 key + 空标记），best-effort 不抛出。 */
    private void invalidateQuietly(String... dataKeys) {
        for (String dataKey : dataKeys) {
            try {
                redisAccess.executeVoid(j -> {
                    j.del(dataKey);
                    j.del(CacheKeys.empty(dataKey));
                });
            } catch (CacheException e) {
                stats.record(CacheStats.Event.WRITE_FAIL, dataKey);
                LOGGER.log(Level.WARNING, "评论缓存失效失败（缓存放任 TTL 自愈）, key=" + dataKey, e);
            }
        }
    }

    private static void collectIdsRecursive(CommentCacheDTO ccVO, List<Long> ids) {
        ids.add(ccVO.getCommentId());
        if (ccVO.getChildren() != null) {
            for (CommentCacheDTO child : ccVO.getChildren()) {
                collectIdsRecursive(child, ids);
            }
        }
    }

    private long ttlSeconds() {
        return AppConfig.getCommentTtlSeconds();
    }

    // ==================== 结果载体 ====================

    /** 分页窗口读结果：该页主楼树 + 真实主楼总数。 */
    public static final class PageWindow {
        private final List<CommentCacheDTO> roots;
        private final int rootTotal;

        PageWindow(List<CommentCacheDTO> roots, int rootTotal) {
            this.roots = roots;
            this.rootTotal = rootTotal;
        }

        static PageWindow empty() {
            return new PageWindow(new ArrayList<>(), 0);
        }

        public List<CommentCacheDTO> getRoots() {
            return roots;
        }

        public int getRootTotal() {
            return rootTotal;
        }
    }

    /** 评论所属内容 + 所属主楼（rootId 为 null = 上溯失败）。 */
    private static final class CommentRef {
        final long contentId;
        final Long rootId;

        CommentRef(long contentId, Long rootId) {
            this.contentId = contentId;
            this.rootId = rootId;
        }
    }

    /** 单次窗口装载结果：主楼行 + 首装时的真实主楼总数（-1 = 非首装不取）。 */
    private static final class RootLoad {
        final List<CommentCacheDTO> mains;
        final int total;

        RootLoad(List<CommentCacheDTO> mains, int total) {
            this.mains = mains;
            this.total = total;
        }
    }
}