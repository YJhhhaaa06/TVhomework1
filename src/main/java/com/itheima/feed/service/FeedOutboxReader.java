package com.itheima.feed.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 大V发件箱读腿（feed2-23 T23）：把若干大V作者的「最近 N 条」合成一路窗口。
 *
 * <p><b>为什么有这一腿</b>：大V的内容**不进粉丝收件箱**（{@code FeedInboxWriter} 命中大V即跳过写扩散），
 * 也**不落表**（反面对照：发件箱落表已判不做）⇒ 读时按作者现拉。数据面 = {@code content} 表走
 * {@code idx_user_id}（InnoDB 物理 {@code (user_id, id)}）**反向索引扫描**取最近 N 条（免 filesort），
 * 与本仓重建侧"{@code ORDER BY id DESC LIMIT K}"是同一口径、**不新增索引**。
 *
 * <p><b>缓存形态</b>：{@code feed:outbox:{authorId}}（ZSet，score = contentId，存该作者最近 N 条）
 * = **可降级读缓存**（真相源始终是 {@code content} 表；miss → 回源 → 回填；Redis 异常 → DB 直查、
 * 不写回）。**两件套**（数据 key + {@code empty:}，见 {@link CacheKeys#feedOutboxCacheKeys(long)}）；
 * 命中顺带 {@code EXPIRE}（滑动续期），空标记不续期。
 *
 * <p><b>一次 pipeline 批量读</b>（用户拍板：大V腿**不封顶**、以 pipeline 吸收往返）：一个连接、
 * 一趟往返内对**所有**大V作者发 {@code EXISTS empty:} / {@code EXISTS key} / {@code ZREVRANGE 0 N-1}
 * / {@code EXPIRE}；miss 作者再**一次带归属的批量 SQL**（{@code ContentDao#findRecentContentIdsByAuthor}）
 * 回源、一趟 pipeline 写回。⇒ 读的往返次数与"关注的大V作者数"**解耦**。
 *
 * <p>⚠️ 规模口径（登记）：本腿产出 id 数 ∝ **关注的大V作者数 × N**，而关注数无上限
 * （{@code FollowService.follow} 无数量限制）⇒ 合并前的规模可远超读侧总窗口 M——**按设计截断在归并后**
 * （截断由 {@code FeedReadService} 统一做）。进一步优化属"读成本优化"，已判**不做、不登记**。
 *
 * <p><b>失败面（一律"不抛"）</b>：
 * <ul>
 *   <li>Redis 读异常 → 记 WARNING（**持栈**，本链唯一捕获点）+ 全量 DB 回源、**不写回**（降级不放量）；</li>
 *   <li>DB 回源异常 → 记 WARNING 结论行（源头在事务回调内已持 SEVERE + 栈）+ 本批**按空处理**、
 *       **不写空标记**（避免把一次失败固化成"该作者没有内容"的假空）；</li>
 *   <li>回填写失败 → 记 WARNING 结论行 + {@code WRITE_FAIL} 打点（读自愈；本次结果照常返回）。</li>
 * </ul>
 *
 * <p><b>日志口径（§3.1）</b>：每类失败只留**一条**记录——Redis 读失败由本类持栈；DB 回源失败的
 * SEVERE + 栈在事务回调（包装点即源头）、本类只补结论行不带栈；回填写失败不带栈（不影响本次结果）。
 */
@Component
public class FeedOutboxReader {

    private static final Logger LOGGER = LogUtil.getLogger(FeedOutboxReader.class);

    private final RedisAccess redis;
    private final CacheAside cacheAside;
    private final CacheStats stats;
    private final ContentDao contentDao;
    private final TransactionTemplate transactionTemplate;

    @InjectConstructor
    public FeedOutboxReader(RedisAccess redis, CacheAside cacheAside, CacheStats stats,
                            ContentDao contentDao, TransactionTemplate transactionTemplate) {
        this.redis = redis;
        this.cacheAside = cacheAside;
        this.stats = stats;
        this.contentDao = contentDao;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 读若干大V作者的"最近 N 条"并**拼成一路**（各作者内部降序；作者间顺序不影响后续归并）。
     *
     * <p>**不去重、不排序、不截断**——这些统一由 {@code FeedReadService} 的
     * {@code mergeDedupSortTrim} 完成（口径单一来源）。
     *
     * @param authorIds 大V作者集合（空 / null → 空列表）
     * @return 所有作者 outbox id 的并集（可能含重复；降序在后段归并时统一处理）
     */
    public List<Long> readOutbox(Set<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptyList();
        }
        int windowSize = AppConfig.getFeedOutboxWindowSize();
        if (windowSize <= 0) {
            // 防御：N<=0 时 `ZREVRANGE 0 N-1` 会退化成 `0 -1`（读**全量**）——与回源侧
            //（`ContentDao.findRecentContentIdsByAuthor` 对 limit<=0 直接返回空）语义相反，故此处取空、
            // 不放行"读全量"这条静默放大路径（`app.properties` 现值 20，异常配置下取空更安全）。
            LOGGER.log(Level.WARNING, "大V发件箱窗口大小配置非法（按空处理）, windowSize=" + windowSize);
            return Collections.emptyList();
        }
        long ttl = AppConfig.getFeedOutboxTtlSeconds();

        Map<Long, List<Long>> perAuthor = new LinkedHashMap<>();
        List<Long> misses = new ArrayList<>();
        try {
            redis.executeVoid(jedis -> {
                Pipeline pipeline = jedis.pipelined();
                Map<Long, Response<Boolean>> emptyFlag = new LinkedHashMap<>();
                Map<Long, Response<Boolean>> existsFlag = new LinkedHashMap<>();
                Map<Long, Response<List<String>>> window = new LinkedHashMap<>();
                for (Long authorId : authorIds) {
                    String key = CacheKeys.feedOutbox(authorId);
                    emptyFlag.put(authorId, pipeline.exists(CacheKeys.empty(key)));
                    existsFlag.put(authorId, pipeline.exists(key));
                    window.put(authorId, pipeline.zrevrange(key, 0, windowSize - 1L));
                    // 滑动续期：无条件入列（key 不存在返回 0、无副作用）；空标记**不**续期
                    pipeline.expire(key, ttl);
                }
                pipeline.sync();
                for (Long authorId : authorIds) {
                    String key = CacheKeys.feedOutbox(authorId);
                    if (Boolean.TRUE.equals(emptyFlag.get(authorId).get())) {
                        stats.record(CacheStats.Event.HIT_EMPTY, key);
                        perAuthor.put(authorId, Collections.emptyList());
                    } else if (Boolean.TRUE.equals(existsFlag.get(authorId).get())) {
                        stats.record(CacheStats.Event.HIT_DATA, key);
                        perAuthor.put(authorId, toLongList(window.get(authorId).get()));
                    } else {
                        stats.record(CacheStats.Event.MISS, key);
                        misses.add(authorId);
                    }
                }
            });
        } catch (CacheException e) {
            // 本链 Redis 读的唯一捕获点 → 持栈；降级不放量：全量 DB 回源、不写回
            LOGGER.log(Level.WARNING, "大V发件箱缓存读失败，降级 DB, authorCount=" + authorIds.size(), e);
            for (Long authorId : authorIds) {
                stats.record(CacheStats.Event.DEGRADE, CacheKeys.feedOutbox(authorId));
            }
            misses.clear();
            misses.addAll(authorIds);
            return loadAllFromDb(misses);
        }

        if (!misses.isEmpty()) {
            Map<Long, List<Long>> loaded = loadOutboxFromDb(misses);   // 一次带归属批量回源
            for (Long authorId : misses) {
                stats.record(CacheStats.Event.LOAD, CacheKeys.feedOutbox(authorId));
                List<Long> ids = (loaded == null) ? Collections.emptyList()
                        : loaded.getOrDefault(authorId, Collections.emptyList());
                perAuthor.put(authorId, ids);
            }
            if (loaded != null) {
                backfill(loaded, misses, ttl);   // 回源成功才回填（失败不写空标记）
            }
        }

        List<Long> merged = new ArrayList<>();
        for (Long authorId : authorIds) {
            merged.addAll(perAuthor.getOrDefault(authorId, Collections.emptyList()));
        }
        return merged;
    }

    // ==================== 回源 ====================

    /** 降级路径：全量作者一次批量回源（不进 Redis、不写回）。 */
    private List<Long> loadAllFromDb(List<Long> authorIds) {
        Map<Long, List<Long>> loaded = loadOutboxFromDb(authorIds);
        if (loaded == null) {
            return Collections.emptyList();
        }
        List<Long> merged = new ArrayList<>();
        for (List<Long> ids : loaded.values()) {
            merged.addAll(ids);
        }
        return merged;
    }

    /**
     * 带归属批量回源：一次 SQL 取回各 author 的最近 N 条（{@code UNION ALL} 每分支多选一列
     * {@code user_id AS author_id}，故结果可**按作者切分**、直接用于逐作者回填）。
     *
     * @return 作者 id → 最近 N 条（降序）；**回源失败返回 {@code null}**（= 降级信号：调用方按空处理且不回填）
     */
    private Map<Long, List<Long>> loadOutboxFromDb(List<Long> authorIds) {
        int windowSize = AppConfig.getFeedOutboxWindowSize();
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return contentDao.findRecentContentIdsByAuthor(conn, authorIds, windowSize);
                } catch (SQLException e) {
                    // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈
                    LOGGER.log(Level.SEVERE, "大V发件箱 DB 回源失败, authorCount=" + authorIds.size(), e);
                    throw new ServerException("大V发件箱回源失败");
                }
            });
        } catch (RuntimeException e) {
            // 源头已持栈 → 此处只记结论行、不带栈；返回 null 表示"降级"（不回填、不写空标记）
            LOGGER.log(Level.WARNING, "大V发件箱回源降级（本次按空处理，不回填）, authorCount="
                    + authorIds.size());
            return null;
        }
    }

    // ==================== 回填 ====================

    /**
     * 回源结果回填（一趟 pipeline）：非空 → {@code ZADD}（score = contentId）+ {@code EXPIRE}；
     * 空 → {@link CacheAside#markEmpty(String)}（含"数据 key 不存在才写"的存在守卫，与既有空标记同源）。
     *
     * <p>失败只记 WARNING 结论行 + {@code WRITE_FAIL} 打点、**不抛出**（缓存写失败不得让读失败；
     * 残留由 TTL / 下次发布失效兜底）。
     */
    private void backfill(Map<Long, List<Long>> loaded, List<Long> authorIds, long ttl) {
        List<Long> emptyAuthors = new ArrayList<>();
        try {
            redis.executeVoid(jedis -> {
                Pipeline pipeline = jedis.pipelined();
                for (Long authorId : authorIds) {
                    List<Long> ids = loaded.get(authorId);
                    if (ids == null || ids.isEmpty()) {
                        emptyAuthors.add(authorId);
                        continue;
                    }
                    String key = CacheKeys.feedOutbox(authorId);
                    Map<String, Double> scored = new LinkedHashMap<>();
                    for (Long id : ids) {
                        scored.put(String.valueOf(id), id.doubleValue());   // score = contentId（同收件箱口径）
                    }
                    pipeline.zadd(key, scored);
                    pipeline.expire(key, ttl);
                }
                pipeline.sync();
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "大V发件箱回填失败，读自愈, authorCount=" + authorIds.size(), e);
            stats.record(CacheStats.Event.WRITE_FAIL, CacheKeys.feedOutbox(authorIds.get(0)));
            return;   // 一次性写失败 ⇒ 不再尝试空标记（同一依赖故障，不放大）
        }
        for (Long authorId : emptyAuthors) {
            cacheAside.markEmpty(CacheKeys.feedOutbox(authorId));   // 内部自行捕获，不抛
        }
    }

    /** {@code ZREVRANGE} 结果（String，已按 score 降序）转 Long 列表。 */
    private static List<Long> toLongList(List<String> members) {
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(members.size());
        for (String member : members) {
            ids.add(Long.valueOf(member));
        }
        return ids;
    }
}
