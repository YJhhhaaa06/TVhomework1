package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.ZSetCache;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 写扩散落库（feed2-21 T21；由 feed1-18 的 {@code FeedInboxCache} 改写）：把一条新内容写进作者每个
 * 粉丝的收件箱 **DB 真相表** {@code feed_inbox}，并对这些粉丝的收件箱 **Redis 读缓存**做**写后失效
 * DEL**——一致性口径 = **单写 DB 真相 + 写后失效 + 读 miss 回源回填**（NEEDS 4.0 写侧拍板）。
 *
 * <p><b>与一期（T18）的差异</b>：一期写的是 Redis ZSet（派生副本）、永不 DEL；二期起 DB 表 = 时间线
 * 真相源，fanout **不再写 Redis**，只做失效——因此本批粉丝的 {@code feed:inbox:{fanId}} 缓存
 * （按既有约定**三件套**：数据 key + `empty:` + `partial:`）与遗留的 {@code feed:inbox:full:{fanId}}
 * 完整态标记**一并 DEL**（标记的数据被 DEL 后残留必失真——影子核对工具会把它误判为"有标记可比对"；
 * 标记语义本身随 R-13 于 T22 迁"窗口同步状态"落表。失效集口径见 {@link #deleteCacheKeys}）。
 *
 * <p><b>大V路由（单点）</b>：判定走 {@link FeedBigVRouter}（固定阈值 + 名单占位，fanout / 重建 / 读
 * 三处同源）——命中即**跳过本次写扩散**（大V内容由"大V发件箱"读时拉，T23），只记 FINE（常态路由，
 * 无需人介入）。
 *
 * <p><b>粉丝列表读法</b>（一期红线沿用）：**不新增全量粉丝读**，一律走
 * {@link FollowCache#getFollowerWindow} 的窗口迭代（{@code [offset, offset+BATCH)}），
 * 装载量与页大小相关而非粉丝总量（治 U-18 同型隐患）。
 *
 * <p><b>每批三步</b>（顺序不可交换：先 DB 真相、后缓存失效）：① DB 批量 `INSERT IGNORE`（幂等，
 * 重复投递无副作用）→ ② 单命令批量 `DEL`（三件套 + 标记）；一轮窗口内 ① 借一次 DB 连接、
 * ② 借一次 Redis 连接（不逐粉丝各借还一次）。
 *
 * <p><b>失败面</b>（契约"绝不抛"，末尾 SEVERE 兜底；消费侧一律降级 ACK，不转死信）：
 * <ul>
 *   <li>粉丝窗口读取失败 → 记 WARNING（结论行，源头持栈）并中止本次 fanout；</li>
 *   <li>DB 落库失败 → 记 WARNING（结论行，源头持栈）并**短路**本次 fanout（同旧 Redis 写失败口径：
 *       外部依赖整体不可用时不得把"窗口 DB 查 + 降级日志"按批数各刷一遍）；未落库的粉丝由后续重建 /
 *       丢消息兜底路径自愈（可靠性加固属三期）；</li>
 *   <li>缓存失效 DEL 失败 → **不停写**（DB 真相优先）：首次记 WARNING（**持栈**——该链唯一捕获点）
 *       + 打点一次（不按批刷），并**停用后续批次的失效尝试**（首次失败即视为"缓存不可用"，同 T18
 *       "不放大依赖故障"取向）；落库一路继续到底，残留缓存由 TTL / 重建 / 下次写失效兜底。</li>
 * </ul>
 *
 * <p><b>日志与打点口径</b>：窗口 / DB 两类失败里源头（{@code FollowCache.loadIds} / 事务回调）已持
 * SEVERE + 栈（§3.1 附加纪律 2）——本类只补结论行、不带栈；DEL 失败是本链唯一捕获点 → 持栈。
 * 打点：DEL 失败记 {@code WRITE_FAIL}，dataKey 取本批首个收件箱 key ⇒ 归 **FEED** 域。
 */
@Component
public class FeedInboxWriter {

    private static final Logger LOGGER = LogUtil.getLogger(FeedInboxWriter.class);

    /**
     * 粉丝窗口迭代批量（包内常量，对齐 mq 包"非必要不入配置"口径）：一期发布量低，
     * 该值只影响"每轮 DB 查询 + DB/Redis 往返次数"，不需按环境调参；三期做阈值/活跃策略时再议。
     */
    static final int FANOUT_BATCH = 200;

    private final FollowCache followCache;
    private final FeedInboxDao feedInboxDao;
    private final TransactionTemplate transactionTemplate;
    private final FeedBigVRouter bigVRouter;
    private final RedisAccess redis;
    private final CacheStats stats;

    @InjectConstructor
    public FeedInboxWriter(FollowCache followCache, FeedInboxDao feedInboxDao,
                           TransactionTemplate transactionTemplate, FeedBigVRouter bigVRouter,
                           RedisAccess redis, CacheStats stats) {
        this.followCache = followCache;
        this.feedInboxDao = feedInboxDao;
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
        if (bigVRouter.isBigV(authorId)) {
            // 大V内容不进粉丝收件箱（由"大V发件箱"读时拉，T23）——常态路由，FINE 即可
            LOGGER.fine("写扩散跳过大V, contentId=" + contentId + ", authorId=" + authorId);
            return;
        }
        long offset = 0L;
        boolean delAbandoned = false;
        while (true) {
            ZSetCache.Window window;
            try {
                window = followCache.getFollowerWindow(authorId, offset, FANOUT_BATCH);
            } catch (RuntimeException e) {
                // loader 的 DB 失败：源头已持 SEVERE + 栈（FollowCache.loadIds）→ 此处只记结论行（不带栈）
                LOGGER.log(Level.WARNING, "写扩散中止（粉丝窗口读取失败，不影响发布）, contentId=" + contentId
                        + ", authorId=" + authorId + ", offset=" + offset);
                return;
            }
            List<Long> fanIds = window.getIds();
            if (fanIds.isEmpty()) {
                return;
            }
            if (!insertBatch(contentId, fanIds)) {
                return;
            }
            if (!delAbandoned) {
                // 首次 DEL 失败即视为"缓存不可用"，后续批次不再尝试（同 T18"不放大依赖故障"取向；
                // DB 真相优先，落库一路继续到底，残留缓存由 TTL / 重建 / 下次写失效兜底）
                delAbandoned = !delBatch(contentId, fanIds);
            }
            if (fanIds.size() < FANOUT_BATCH) {
                // 不足一批 = DB 已到底（ZSetCache 窗口装载的既有终止口径，不依赖 total，防计数漂移）
                return;
            }
            offset += fanIds.size();
        }
    }

    /**
     * 单批缓存失效（首次失败即持栈 WARNING + 打点一次，之后调用方停用本通道）。
     *
     * @return true = 本批失效命令已发出；false = Redis 不可用（已记录，调用方应停止后续失效尝试）
     */
    private boolean delBatch(long contentId, List<Long> fanIds) {
        try {
            redis.executeVoid(jedis -> deleteCacheKeys(jedis, fanIds));
            return true;
        } catch (RuntimeException e) {
            // 捕获面取 RuntimeException（含 CacheException）：RedisAccess 还有非 CacheException 的出口
            // （如 action 为 null 的 IAE、熔断判断在 try 之外）——若逃出去会触发末尾 SEVERE 兜底、
            // 使"DEL 失败不停写"失效（先例 FeedRebuildService.tryAcquireLock 同口径）。
            // DEL 失败 = 本链唯一捕获点 → 持栈 + 打点一次（不按批刷）
            LOGGER.log(Level.WARNING, "写扩散收件箱缓存失效失败（读自愈兜底，DB 真相不受影响）, contentId="
                    + contentId + ", fanCount=" + fanIds.size(), e);
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
     * 单批缓存失效：一次 `DEL` 多键（每粉丝 **4 键** = 收件箱缓存 + 空标记 + 部分装载标记 + 遗留完整态标记）。
     *
     * <p>失效集口径：收件箱缓存 `feed:inbox:{id}` 按本仓既有约定**三件套一起 DEL**（数据 key + `empty:` +
     * `partial:`，先例 `FollowCache.invalidateKeysQuietly` / {@link CacheKeys#partial(String)} 的
     * "写路径失效三件套"警示）——T23 的收件箱窗口读（Redis→DB→回填）将复用同一数据 key，
     * 残留 `partial:` 会把"前缀"误判为完整集合（静默漏成员），故失效点（本方法）必须现在就把三件套删净。
     *
     * <p>完整态标记一并 DEL 的理由见类注释（与 T18/T19"fanout 永不触碰标记"口径的差异已登记任务回写）。
     */
    private static void deleteCacheKeys(Jedis jedis, List<Long> fanIds) {
        String[] keys = new String[fanIds.size() * 4];
        for (int i = 0; i < fanIds.size(); i++) {
            long fanId = fanIds.get(i);
            String inboxKey = CacheKeys.feedInbox(fanId);
            keys[4 * i] = inboxKey;
            keys[4 * i + 1] = CacheKeys.empty(inboxKey);
            keys[4 * i + 2] = CacheKeys.partial(inboxKey);
            keys[4 * i + 3] = CacheKeys.feedInboxFull(fanId);
        }
        jedis.del(keys);
    }
}