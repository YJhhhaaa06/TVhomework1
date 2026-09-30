package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.dao.FollowDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 写扩散落库（feed2-21 T21；由 feed1-18 的 {@code FeedInboxCache} 改写）：把一条新内容写进作者每个
 * 粉丝的收件箱 **DB 真相表** {@code feed_inbox}，并对这些粉丝的收件箱 **Redis 读缓存**做**写后失效
 * DEL**——一致性口径 = **单写 DB 真相 + 写后失效 + 读 miss 回源回填**（NEEDS 4.0 写侧拍板）。
 *
 * <p><b>与一期（T18）的差异</b>：一期写的是 Redis ZSet（派生副本）、永不 DEL；二期起 DB 表 = 时间线
 * 真相源，fanout **不再写 Redis**，只做失效——因此本批粉丝的 {@code feed:inbox:{fanId}} 缓存
 * （按既有约定**三件套**：数据 key + `empty:` + `partial:`）**一并 DEL**。失效集口径见
 * {@link #deleteCacheKeys}（重组为 {@link CacheKeys#feedInboxCacheKeys(long)} 单一来源，
 * 与重建侧写后失效同源）。
 *
 * <p><b>大V路由（单点）</b>：判定走 {@link FeedBigVRouter}（固定阈值 + 名单占位，fanout / 重建 / 读
 * 三处同源）——命中即**跳过本次写扩散**（大V内容由"大V发件箱"读时拉，T23），只记 FINE（常态路由，
 * 无需人介入）。
 *
 * <p><b>降级补推（feed3-T28-B）</b>：第二个公开入口 {@link #backfillAuthor(long)} ——作者由大V降为
 * 普通（滞回判定 edge）后，把其**最近 K 条**内容补写进**现任粉丝**收件箱（"复用 fanout 形态"：
 * 同一条游标迭代 + `INSERT IGNORE` + 写后失效三件套）。与 fanout 的两处差别：① 消费时**复查**
 * 大V（已重升级 ⇒ 早退，可见性由发件箱腿保证，不造无谓上行残影行）；② **不失效发件箱缓存**
 * （补推不产生新内容，且发件箱缓存的新鲜度已由"每次发布无条件 DEL"与作者身份解耦）。
 * 补推**零删除**、重复投递幂等（唯一键 + IGNORE）；失败只降级（缺口补偿统一归 feed3-T33）。
 *
 * <p><b>粉丝列表读法</b>（feed3-T30 改写；一期红线"不新增全量粉丝读"沿用）：**游标（keyset）直读
 * DB、不回填缓存**——逐批 {@link FollowDao#getFollowerUserIdsAfter}（{@code user_id > cursor} 升序
 * 取一批，走既有 {@code idx_followed_user_user}、免 filesort；返回不足批 = DB 已到底），装载量只与
 * 批大小相关而非粉丝总量；**不再触碰** {@code FollowCache.getFollowerWindow} ⇒ 发布路径**不再物化
 * `user:follower` zset**（治 N6 的被动装载；该窗口读的唯一剩余调用方 = 粉丝列表分页，语义零改动）。
 *
 * <p><b>游标并发口径（登记）</b>：游标严格递增（{@code user_id > cursor}）⇒ 同一遍历**不重**，
 * 也不受并发插入 / 删除引起的行位移影响（对照 OFFSET 的重复 / 跳行）；遍历期间**新增关注**若其
 * {@code user_id} ≤ 当前游标则本轮可能漏——由其关注动作触发的收件箱重建兜底；遍历期间**取关者**
 * 可能仍被写入（与旧窗口快照口径一致）——由下次重建清理（"只多不丢"不变量不破）。
 *
 * <p><b>每批三步</b>（顺序不可交换：先 DB 真相、后缓存失效）：① 游标读一批粉丝（DB）→ ② DB 批量
 * `INSERT IGNORE`（幂等，重复投递无副作用）→ ③ 单命令批量 `DEL`（三件套）；一轮批内读 / 写各借一次
 * DB 连接、失效借一次 Redis 连接（不逐粉丝各借还一次）。
 *
 * <p><b>大V发件箱写后失效（feed2-23 T23）</b>：进入本方法**先**做一次
 * {@code DEL feed:outbox:{authorId}}（两件套：数据 key + {@code empty:}，见
 * {@link CacheKeys#feedOutboxCacheKeys(long)}），**无条件**且**位于大V判定之前**——大V分支命中即早退，
 * 若把该失效放进后面的收件箱失效段，则大V发布**永不执行它**（读侧会一直读到旧发件箱内容）。
 * 它与收件箱失效**共用**"首次失败即停用后续失效尝试"的开关（{@code delAbandoned} 由其返回值初始化），
 * 以使一次 Redis 故障只留**一条**带堆栈记录（§3.1 附加纪律 2）。
 *
 * <p><b>失败面</b>（契约"绝不抛"，末尾 SEVERE 兜底；消费侧一律降级 ACK，不转死信）：
 * <ul>
 *   <li>粉丝游标读取失败 → 记 WARNING（结论行，源头持栈）并中止本次 fanout；</li>
 *   <li>DB 落库失败 → 记 WARNING（结论行，源头持栈）并**短路**本次 fanout（同旧 Redis 写失败口径：
 *       外部依赖整体不可用时不得把"窗口 DB 查 + 降级日志"按批数各刷一遍）；未落库的粉丝由后续重建 /
 *       丢消息兜底路径自愈（可靠性加固属三期）；</li>
 *   <li>缓存失效 DEL 失败 → **不停写**（DB 真相优先）：首次记 WARNING（**持栈**——该链唯一捕获点）
 *       + 打点一次（不按批刷），并**停用后续批次的失效尝试**（首次失败即视为"缓存不可用"，同 T18
 *       "不放大依赖故障"取向）；落库一路继续到底，残留缓存由 TTL / 重建 / 下次写失效兜底。</li>
 * </ul>
 *
 * <p><b>日志与打点口径</b>：游标读 / DB 落库两类失败里源头（本类事务回调——
 * {@code readFollowerBatch} / {@code insertBatch}）已持 SEVERE + 栈（§3.1 附加纪律 2）——本类
 * 只补结论行、不带栈；DEL 失败是本链唯一捕获点 → 持栈。
 * 打点：DEL 失败记 {@code WRITE_FAIL}，dataKey 取本批首个收件箱 key ⇒ 归 **FEED** 域。
 */
@Component
public class FeedInboxWriter {

    private static final Logger LOGGER = LogUtil.getLogger(FeedInboxWriter.class);

    private final FollowDao followDao;
    private final FeedInboxDao feedInboxDao;
    /** 降级补推取"作者最近 K 条内容"（feed3-T28-B；feed → content 依赖已存在，非新包边）。 */
    private final ContentDao contentDao;
    private final TransactionTemplate transactionTemplate;
    private final FeedBigVRouter bigVRouter;
    private final RedisAccess redis;
    private final CacheStats stats;

    @InjectConstructor
    public FeedInboxWriter(FollowDao followDao, FeedInboxDao feedInboxDao, ContentDao contentDao,
                           TransactionTemplate transactionTemplate, FeedBigVRouter bigVRouter,
                           RedisAccess redis, CacheStats stats) {
        this.followDao = followDao;
        this.feedInboxDao = feedInboxDao;
        this.contentDao = contentDao;
        this.transactionTemplate = transactionTemplate;
        this.bigVRouter = bigVRouter;
        this.redis = redis;
        this.stats = stats;
    }

    /**
     * 写扩散一条内容：向作者的所有粉丝收件箱落库 {@code contentId}，并失效其收件箱缓存。
     *
     * <p>幂等（INSERT IGNORE；同一内容重复投递无副作用）。任何失败都不抛出。
     *
     * @param contentId 新内容 id
     * @param authorId  作者 id（大V判定 + 粉丝列表来源）
     */
    public void fanout(long contentId, long authorId) {
        try {
            doFanout(contentId, authorId);
        } catch (RuntimeException e) {
            // 契约"绝不抛"的最后兜底：不得让异常穿透消费容器去转死信（需人介入 → SEVERE + 栈）
            LOGGER.log(Level.SEVERE, "写扩散异常（已兜底，不影响发布）, contentId=" + contentId, e);
        }
    }

    private void doFanout(long contentId, long authorId) {
        // ① 大V发件箱写后失效（feed2-23 T23）：**无条件、且必须先于大V早退**——下面那个分支命中即
        //    return，若把本步骤挪到收件箱失效段里，大V发布将永不动它（读侧一直读到旧发件箱）。
        //    顺带把"Redis 是否可用"这一结果传给收件箱失效段，避免同一故障刷两条带栈记录。
        boolean delAbandoned = !invalidateOutbox(authorId);
        if (bigVRouter.isBigV(authorId)) {
            // 大V内容不进粉丝收件箱（由"大V发件箱"读时拉，T23）——常态路由，FINE 即可
            LOGGER.fine("写扩散跳过大V, contentId=" + contentId + ", authorId=" + authorId);
            return;
        }
        long cursor = 0L;   // keyset 游标：users.id 为正 ⇒ user_id > 0 覆盖全体粉丝
        // 粉丝游标迭代批量（feed3-T29：由配置提供——键 feed.fanout.batch，默认 200；非正数校验在 AppConfig）。
        // 只影响"每轮 DB 查询 + DB/Redis 往返次数"，不改写扩散语义。
        final int fanoutBatch = AppConfig.getFeedFanoutBatch();
        while (true) {
            List<Long> fanIds;
            try {
                fanIds = readFollowerBatch(authorId, cursor, fanoutBatch);
            } catch (RuntimeException e) {
                // 游标查询的 DB 失败：源头回调已持 SEVERE + 栈 → 此处只记结论行（不带栈）
                LOGGER.log(Level.WARNING, "写扩散中止（粉丝游标读取失败，不影响发布）, contentId=" + contentId
                        + ", authorId=" + authorId + ", cursor=" + cursor);
                return;
            }
            if (fanIds.isEmpty()) {
                return;
            }
            if (!insertBatch(contentId, fanIds)) {
                return;
            }
            if (!delAbandoned) {
                // 首次 DEL 失败即视为"缓存不可用"，后续批次不再尝试（同 T18"不放大依赖故障"取向；
                // DB 真相优先，落库一路继续到底，残留缓存由 TTL / 重建 / 下次写失效兜底）
                delAbandoned = !delInboxBatch("contentId=" + contentId, fanIds);
            }
            if (fanIds.size() < fanoutBatch) {
                // 不足一批 = DB 已到底（游标窗口的既有终止口径，不依赖 total，防计数漂移）
                return;
            }
            cursor = fanIds.get(fanIds.size() - 1);   // 末位 id 作下一页游标（keyset：严格递增 ⇒ 不重）
        }
    }

    /**
     * 单批粉丝游标读（feed3-T30）：keyset 直读 DB、**不回填缓存**——{@code user_id > cursor} 升序取
     * {@code batch} 行（走既有 {@code idx_followed_user_user}，免 filesort；返回不足批 = DB 已到底）。
     * fanout 与降级补推（feed3-T28-B）**共用**本次遍历语义。
     *
     * <p>SQLException 在本链唯一捕获点（回调即源头，§3.1 附加纪律 2）记 SEVERE + 栈后包
     * {@link ServerException}；调用方只补结论行（不带栈）。读与写各自独立事务连接借还（不合并）。
     */
    private List<Long> readFollowerBatch(long authorId, long cursor, int batch) {
        return transactionTemplate.execute(conn -> {
            try {
                return followDao.getFollowerUserIdsAfter(conn, authorId, cursor, batch);
            } catch (SQLException e) {
                // 该链唯一捕获点（包装点即源头）：SEVERE + 栈；明细只记 authorId/cursor/批量
                LOGGER.log(Level.SEVERE, "写扩散粉丝游标查询失败, authorId=" + authorId
                        + ", cursor=" + cursor + ", batch=" + batch, e);
                throw new ServerException("写扩散粉丝游标查询失败");
            }
        });
    }

    // ==================== 降级补推（feed3-T28-B） ====================

    /**
     * 降级补推：把作者（由大V降为普通）的**最近 K 条**内容补写进其**现任粉丝**收件箱。
     *
     * <p><b>形态 = "复用 fanout"</b>（NEEDS 4.0 T28 拍板④）：同一条粉丝游标迭代 + `INSERT IGNORE` +
     * 写后失效三件套；K = {@link AppConfig#getFeedInboxWindowPerAuthor()}（默认 20，与重建 / 发件箱
     * 窗口 N 同量级 ⇒ 降级前后可见性范围一致）。
     *
     * <p><b>零删除</b>：只追增，不触碰二期"fanout 只追增 ⇒ 只多不丢"不变量；`INSERT IGNORE` 幂等
     * （MQ automatic recovery 重发无副作用）。**不依赖**粉丝的下一次关注 / 取关。
     *
     * <p><b>消费时复查大V</b>：若消费时刻作者**已重新升级**（降级后又被关注回线）⇒ 跳过——可见性
     * 由"大V发件箱腿"保证（N == K 同量级），不产生无谓的上行残影行；再次降级会有新 edge、新补推。
     *
     * <p><b>失败面</b>（契约"绝不抛"，末尾 SEVERE 兜底；补推失败只降级，缺口补偿统一归 feed3-T33）：
     * <ul>
     *   <li>存量内容读取失败 → 结论行 WARNING（不带栈；源头回调已持 SEVERE + 栈）并中止；</li>
     *   <li>粉丝游标读取失败 → 记 WARNING（结论行）并中止（口径同 fanout）；</li>
     *   <li>落库失败 → 记 WARNING（结论行，源头持栈）并**短路**本次补推（同"外部依赖整体不可用
     *       不按批刷日志"口径）；</li>
     *   <li>缓存失效 DEL 失败 → **不停写**（DB 真相优先）：首次记 WARNING + 打点一次（不按批刷）
     *       并停用后续批次的失效尝试。</li>
     * </ul>
     *
     * @param authorId 降级的作者（现任粉丝 = 消费时刻 {@code follow} 表中其关注者集合）
     */
    public void backfillAuthor(long authorId) {
        try {
            doBackfill(authorId);
        } catch (RuntimeException e) {
            // 契约"绝不抛"的最后兜底：不得让异常穿透消费容器去转死信（需人介入 → SEVERE + 栈）
            LOGGER.log(Level.SEVERE, "降级补推异常（已兜底，不影响关注/取关）, authorId=" + authorId, e);
        }
    }

    private void doBackfill(long authorId) {
        // 消费时复查（与 fanout 早退同形）：降级后又被关注回线 ⇒ 本批补推无必要（发件箱腿保证可见）
        if (bigVRouter.isBigV(authorId)) {
            LOGGER.fine("降级补推跳过（消费时作者已是大V，由发件箱腿保证可见）, authorId=" + authorId);
            return;
        }
        List<Long> contentIds;
        try {
            contentIds = loadRecentContentIds(authorId);
        } catch (ServerException e) {
            // 源头（查询回调 / TransactionTemplate）已持 SEVERE + 栈 → 此处只记结论行、不带栈
            LOGGER.log(Level.WARNING, "降级补推中止（存量内容读取失败，降级）, authorId=" + authorId);
            return;
        }
        if (contentIds.isEmpty()) {
            // 无存量内容 ⇒ 无行可补（不读粉丝、不发 SQL、不失效缓存）
            return;
        }
        long cursor = 0L;   // keyset 游标：users.id 为正 ⇒ user_id > 0 覆盖全体粉丝
        // 批量尺寸复用 fanout 的执行参数（feed.fanout.batch，默认 200）：只影响往返次数，不改语义
        final int fanoutBatch = AppConfig.getFeedFanoutBatch();
        boolean delAbandoned = false;
        while (true) {
            List<Long> fanIds;
            try {
                fanIds = readFollowerBatch(authorId, cursor, fanoutBatch);
            } catch (RuntimeException e) {
                // 游标查询的 DB 失败：源头回调已持 SEVERE + 栈 → 此处只记结论行（不带栈）
                LOGGER.log(Level.WARNING, "降级补推中止（粉丝游标读取失败，降级）, authorId=" + authorId
                        + ", cursor=" + cursor);
                return;
            }
            if (fanIds.isEmpty()) {
                return;
            }
            if (!insertBackfillBatch(authorId, contentIds, fanIds)) {
                return;
            }
            if (!delAbandoned) {
                // 首次 DEL 失败即视为"缓存不可用"，后续批次不再尝试（同 fanout 口径）
                delAbandoned = !delInboxBatch("authorId=" + authorId, fanIds);
            }
            if (fanIds.size() < fanoutBatch) {
                // 不足一批 = DB 已到底（游标窗口的既有终止口径）
                return;
            }
            cursor = fanIds.get(fanIds.size() - 1);
        }
    }

    /**
     * 取作者最近 K 条内容 id（{@code is_deleted = 0}，降序；K = 重建 / 收件箱窗口口径）。
     *
     * <p>SQLException 在本链唯一捕获点（回调即源头，§3.1 附加纪律 2）记 SEVERE + 栈后包
     * {@link ServerException}；调用方只补结论行（不带栈）。
     */
    private List<Long> loadRecentContentIds(long authorId) {
        int perAuthorLimit = AppConfig.getFeedInboxWindowPerAuthor();
        return transactionTemplate.execute(conn -> {
            try {
                Map<Long, List<Long>> byAuthor =
                        contentDao.findRecentContentIdsByAuthor(conn, List.of(authorId), perAuthorLimit);
                List<Long> ids = byAuthor.get(authorId);
                return (ids != null) ? ids : Collections.emptyList();
            } catch (SQLException e) {
                // 该链唯一捕获点（包装点即源头）：SEVERE + 栈；明细只记 authorId / K
                LOGGER.log(Level.SEVERE, "降级补推存量内容查询失败, authorId=" + authorId
                        + ", perAuthorLimit=" + perAuthorLimit, e);
                throw new ServerException("降级补推存量内容查询失败");
            }
        });
    }

    /**
     * 单批补推落库（独立事务连接借还）：**同一事务内逐内容** {@code INSERT IGNORE}（K 条语句 / 批，
     * 单条参数规模 = 2 × 批量 ≤ 400——按内容而非按"粉丝 × 内容"对拼条，避免放大单条语句规模）。
     *
     * <p>SQLException 在本链唯一捕获点（回调即源头）记 SEVERE + 栈后包 {@link ServerException}；
     * 调用方只补结论行（不带栈）。任一条失败 ⇒ 整批回滚（同"一批同生共死"口径）。
     *
     * @return true = 本批已提交；false = 落库失败（已记结论行，调用方应短路本次补推）
     */
    private boolean insertBackfillBatch(long authorId, List<Long> contentIds, List<Long> fanIds) {
        try {
            transactionTemplate.execute(conn -> {
                try {
                    for (Long contentId : contentIds) {
                        feedInboxDao.insertIgnoreBatch(conn, contentId, fanIds);
                    }
                    return null;
                } catch (SQLException e) {
                    // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈；明细只记规模
                    LOGGER.log(Level.SEVERE, "降级补推收件箱落库失败, authorId=" + authorId
                            + ", contentCount=" + contentIds.size() + ", fanCount=" + fanIds.size(), e);
                    throw new ServerException("降级补推收件箱落库失败");
                }
            });
            return true;
        } catch (ServerException e) {
            // 源头（回调内 / TransactionTemplate）已持 SEVERE + 栈 → 此处只记结论行、不带栈
            LOGGER.log(Level.WARNING, "降级补推中止（收件箱落库失败，降级）, authorId=" + authorId
                    + ", fanCount=" + fanIds.size());
            return false;
        }
    }

    /**
     * 大V发件箱写后失效（feed2-23 T23）：一次 `DEL` 两件套
     * （{@link CacheKeys#feedOutboxCacheKeys(long)} = 数据 key + `empty:`）。
     *
     * <p>为什么无条件（对普通作者也执行）：outbox 缓存的读者只有"大V腿"，但**大V身份会变**
     * （涨粉 / 掉粉）；若只在大V分支里失效，作者从大V降为普通后其 outbox 不再被发布刷新，
     * 而读缓存又"命中即滑动续期"⇒ 重新升为大V时可能读到陈旧窗口。无条件失效把 key 的**内容新鲜度**
     * 与"作者当前是否大V"解耦（代价 = 每次发布多一条 DEL，量级可忽略）。
     *
     * <p>失败面（与收件箱失效同口径）：捕获面取 {@link RuntimeException}（RedisAccess 还有非
     * CacheException 的出口）→ **首次失败即持栈 WARNING + 打点一次**，返回 false 让调用方停用
     * 后续失效尝试（本链唯一捕获点，故持栈；"不停写"——DB 真相一路继续）。
     *
     * @return true = 失效命令已发出；false = Redis 不可用（已记录，调用方应停止后续失效尝试）
     */
    private boolean invalidateOutbox(long authorId) {
        try {
            redis.executeVoid(jedis -> jedis.del(CacheKeys.feedOutboxCacheKeys(authorId)));
            return true;
        } catch (RuntimeException e) {
            // 该链唯一捕获点 → 持栈 + 打点一次（不按批刷）
            LOGGER.log(Level.WARNING, "写扩散大V发件箱缓存失效失败（读自愈兜底，DB 真相不受影响）, authorId="
                    + authorId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, CacheKeys.feedOutbox(authorId));
            return false;
        }
    }

    /**
     * 单批缓存失效（首次失败即持栈 WARNING + 打点一次，之后调用方停用本通道）——fanout（一条内容一批
     * 粉丝）与补推（K 条内容一批粉丝，{@code taskLabel} 带 authorId）共用。
     *
     * @param taskLabel 结论行业务标识片段（{@code contentId=…} / {@code authorId=…}）；只放标识、不含值快照
     * @return true = 本批失效命令已发出；false = Redis 不可用（已记录，调用方应停止后续失效尝试）
     */
    private boolean delInboxBatch(String taskLabel, List<Long> fanIds) {
        try {
            redis.executeVoid(jedis -> deleteCacheKeys(jedis, fanIds));
            return true;
        } catch (RuntimeException e) {
            // 捕获面取 RuntimeException（含 CacheException）：RedisAccess 还有非 CacheException 的出口
            // （如 action 为 null 的 IAE、熔断判断在 try 之外）——若逃出去会触发末尾 SEVERE 兜底、
            // 使"DEL 失败不停写"失效（先例 FeedRebuildService.tryAcquireLock 同口径）。
            // DEL 失败 = 本链唯一捕获点 → 持栈 + 打点一次（不按批刷）
            LOGGER.log(Level.WARNING, "写扩散收件箱缓存失效失败（读自愈兜底，DB 真相不受影响）, "
                    + taskLabel + ", fanCount=" + fanIds.size(), e);
            stats.record(CacheStats.Event.WRITE_FAIL, CacheKeys.feedInbox(fanIds.get(0)));
            return false;
        }
    }

    /**
     * 单批落库（独立事务连接借还；SQLException 在本链唯一捕获点记 SEVERE + 栈后包 {@link ServerException}）。
     *
     * @return true = 本批已提交；false = 落库失败（已记结论行，调用方应短路本次 fanout）
     */
    private boolean insertBatch(long contentId, List<Long> fanIds) {
        try {
            transactionTemplate.execute(conn -> {
                try {
                    return feedInboxDao.insertIgnoreBatch(conn, contentId, fanIds);
                } catch (SQLException e) {
                    // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈；明细只记 contentId/批量
                    LOGGER.log(Level.SEVERE, "写扩散收件箱落库失败, contentId=" + contentId
                            + ", fanCount=" + fanIds.size(), e);
                    throw new ServerException("写扩散收件箱落库失败");
                }
            });
            return true;
        } catch (ServerException e) {
            // 源头（回调内 / TransactionTemplate）已持 SEVERE + 栈 → 此处只记结论行、不带栈
            LOGGER.log(Level.WARNING, "写扩散中止（收件箱落库失败，降级）, contentId=" + contentId
                    + ", fanCount=" + fanIds.size());
            return false;
        }
    }

    /**
     * 单批缓存失效：一次 `DEL` 多键（每粉丝 **3 键** = 收件箱缓存 + 空标记 + 部分装载标记，
     * 口径收敛于 {@link CacheKeys#feedInboxCacheKeys(long)}，与重建侧写后失效同源）。
     *
     * <p>失效集口径：按本仓既有约定**三件套一起 DEL**（数据 key + `empty:` + `partial:`，先例
     * {@code FollowCache.invalidateKeysQuietly} / {@link CacheKeys#partial(String)} 的"写路径失效
     * 三件套"警示）——T23 的收件箱窗口读（Redis→DB→回填）将复用同一数据 key，残留 `partial:`
     * 会把"前缀"误判为完整集合（静默漏成员），故失效点（本方法）必须把三件套删净。
     *
     * <p>T21 时曾一并 DEL 一期完整态标记 {@code feed:inbox:full:{fanId}}；该标记已随
     * **T22 退役**（语义迁 {@code feed_inbox_sync} 表），失效集随之降为三件套。
     */
    private static void deleteCacheKeys(Jedis jedis, List<Long> fanIds) {
        String[] keys = new String[fanIds.size() * 3];
        for (int i = 0; i < fanIds.size(); i++) {
            String[] trio = CacheKeys.feedInboxCacheKeys(fanIds.get(i));
            System.arraycopy(trio, 0, keys, 3 * i, trio.length);
        }
        jedis.del(keys);
    }
}