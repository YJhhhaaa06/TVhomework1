package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.follow.dao.FollowDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.SetParams;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱重建（feed1-19 T19）：{@code DEL → DB 重查 → ZADD 合并} 三步，末尾写"完整态标记"。
 *
 * <p><b>机制与"无丢失窗口"论证</b>（NEEDS 4.0 机制骨架）：收件箱是**派生副本**，真相源是
 * {@code content} + {@code follow} 表。保证不丢内容的正是**三步顺序**（红线，不得调换）：
 * 先 {@code DEL}、再 DB 重查、最后 ZADD。逐情形看——某条内容的 fanout 消息若在当前重建的 DB 重查
 * **之前**被消费，则它的落库必然发生在重查之前（投递点在事务提交后）⇒ 必被本次快照收录；
 * 若在**之后**被消费，则它只可能靠"查完之后"的写进来（本次重建已不再 DEL）⇒ 也不丢。
 * 即 **DEL 之后的写只会"并集"到同一份快照上**（ZADD 可交换、成对无覆盖），故"先 DEL 后 ZADD"
 * 顺序不可交换——交换后（先查后 DEL）在"查完 → DEL"窗口内到达的 fanout 会被 DEL 抹掉而快照里没有。
 *
 * <p><b>fanout 与重建的关系</b>：fanout **永远写**（幂等 ZADD，不检查存在性、不写标记），
 * 重建**先清后建**并写标记；两者交错也不会丢成员（见上）。完整态标记**只由本类写**。
 *
 * <p><b>并发去重</b>：{@code SET NX EX} + Lua CAS 释放（一期唯一分布式锁用法）。锁只是**去重优化**，
 * 不承担正确性：TTL（{@value #REBUILD_LOCK_TTL_SECONDS}s）到点后若真有并发重建交叉执行，最终产物是
 * **两次快照的并集**——**只多不丢**（多出来的成员是"那一刻仍在库里、但已不被本次快照作数"的内容，
 * 由下一次重建收口），故不续期、也不要求锁覆盖全程；该残余窗口登记在任务回写。
 *
 * <p><b>失败面（2026-09-26 裁决）</b>：Redis / DB 失败一律**降级吞掉并 ACK**（与 T18 fanout 同口径；
 * 一期无重试，漏重建由下次关注 / 取关或二期读触发兜底），**不抛给消费容器转死信**——
 * 只有**载荷非法**才在消费者侧抛出转死信。降级后收件箱处于"无标记"态 ⇒ 二期切读会回退拉模式，
 * 对外正确性不依赖推。
 *
 * <p><b>日志口径（§3.1）</b>：一次失败**只允许一条带堆栈记录**——Redis 阶段失败由本类持栈（该链唯一
 * 捕获点）；DB 阶段失败由事务回调持 SEVERE + 栈（源头），本类只补结论行、不带栈；锁释放失败属
 * "非影响性失败"（TTL 兜底、且同链 Redis 故障已在前段报过）故只记 WARNING、不重复带栈。
 * 循环体内不记 INFO；成功路径不刷日志。
 */
@Component
public class FeedRebuildService {

    private static final Logger LOGGER = LogUtil.getLogger(FeedRebuildService.class);

    /** 重建锁 TTL（秒，包内常量，沿 mq 包"非必要不入配置"口径）：仅兜"进程猝死未释放"，到点即视为可重入。 */
    static final long REBUILD_LOCK_TTL_SECONDS = 60L;

    /**
     * DB 重查分页大小（包内常量，与 {@link FeedInboxCache#FANOUT_BATCH} 同值同口径）：
     * 复用拉模式同一 DAO 方法（{@code LIMIT/OFFSET}），**不足一页即到底**——与窗口迭代同终止口径，
     * 不依赖 count（防计数漂移）。
     */
    static final int REBUILD_PAGE_SIZE = 200;

    /** 锁释放：CAS（值等于自己的 token 才删）——避免误删他人已获得的锁。 */
    static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final FollowDao followDao;
    private final ContentDao contentDao;
    private final TransactionTemplate transactionTemplate;
    private final RedisAccess redis;
    private final CacheStats stats;

    @InjectConstructor
    public FeedRebuildService(FollowDao followDao, ContentDao contentDao,
                              TransactionTemplate transactionTemplate, RedisAccess redis,
                              CacheStats stats) {
        this.followDao = followDao;
        this.contentDao = contentDao;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
        this.stats = stats;
    }

    /**
     * 重建 {@code userId} 的收件箱（= 该用户关注者内容的完整快照 + 完整态标记）。**任何失败都不抛出。**
     *
     * @param userId 收件箱归属者（关注 / 取关的发起方）
     */
    public void rebuildInbox(long userId) {
        String token = UUID.randomUUID().toString();
        if (!tryAcquireLock(userId, token)) {
            // 已有并发重建在跑（或 Redis 不可用）：本次跳过。锁是去重优化，跳过不影响正确性。
            LOGGER.fine("收件箱重建跳过（已有并发重建或 Redis 不可用）, userId=" + userId);
            return;
        }
        try {
            deleteInbox(userId);                              // ① DEL（含完整态标记）
            List<Long> contentIds = loadContentIds(userId);    // ② DB 重查（事务内只读）
            writeInbox(userId, contentIds);                   // ③ ZADD 合并 + 写完整态标记
        } catch (CacheException e) {
            // Redis 阶段失败：该链唯一捕获点 → 持栈；降级不抛（裁决：吞掉并 ACK）
            LOGGER.log(Level.WARNING, "收件箱重建失败（降级，读路径不受影响）, userId=" + userId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, CacheKeys.feedInbox(userId));
        } catch (ServerException e) {
            // DB 阶段失败：源头（loadContentIds 的事务回调）已持 SEVERE + 栈 → 此处只记结论行、不带栈
            LOGGER.log(Level.WARNING, "收件箱重建中止（DB 重查失败，降级）, userId=" + userId);
        } catch (RuntimeException e) {
            // 契约"不抛"的最后兜底（需人介入 → SEVERE + 栈）：不得让异常穿透消费容器去转死信
            LOGGER.log(Level.SEVERE, "收件箱重建异常（已兜底，降级）, userId=" + userId, e);
        } finally {
            releaseLock(userId, token);
        }
    }

    // ==================== 锁 ====================

    /**
     * 取锁（{@code SET key token NX EX ttl}）：返回 true = 本次真正执行重建。
     * Redis 不可用时记 WARNING（该链唯一捕获点，持栈）并返回 false——重建整体依赖 Redis，降级跳过。
     */
    private boolean tryAcquireLock(long userId, String token) {
        String key = CacheKeys.feedRebuildLock(userId);
        try {
            String result = redis.execute(jedis ->
                    jedis.set(key, token, SetParams.setParams().nx().ex(REBUILD_LOCK_TTL_SECONDS)));
            return result != null;   // Redis 语义：NX 未命中返回 nil → Jedis 返回 null
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "收件箱重建跳过（Redis 不可用，降级）, userId=" + userId, e);
            return false;
        } catch (RuntimeException e) {
            // 契约"绝不抛"的兜底：RedisAccess 还有非 CacheException 的出口（如 action 为 null 的 IAE、
            // 以及熔断判断在 try 之外）——若逃出去会穿透消费容器去转死信，与"重建失败降级 ACK"的裁决相悖
            LOGGER.log(Level.SEVERE, "收件箱重建取锁异常（已兜底，跳过本次重建）, userId=" + userId, e);
            return false;
        }
    }

    /**
     * 释放锁（Lua CAS，只删自己的 token）。失败只记 WARNING、**不抛**（TTL 兜底）。
     *
     * <p>此处**不带栈**：释放失败不改变本次结果（TTL 到点自动可重入），且同链若发生 Redis 故障
     * 已在前段持过栈——避免"一次失败两条带堆栈记录"（§3.1 附加纪律 2）。捕获面取
     * {@link RuntimeException}（含 {@link CacheException}）以坐实"绝不抛"。
     */
    private void releaseLock(long userId, String token) {
        try {
            redis.executeVoid(jedis -> jedis.eval(RELEASE_LOCK_SCRIPT,
                    List.of(CacheKeys.feedRebuildLock(userId)), List.of(token)));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "收件箱重建锁释放失败（TTL 兜底，无影响）, userId=" + userId);
        }
    }

    // ==================== 三步 ====================

    /**
     * ① DEL 收件箱成员集 + 完整态标记（**必须早于 DB 重查**，见类注释的无丢失窗口论证）。
     * 独立一次连接借还：不在持连接的情况下做后面的 DB I/O。
     */
    private void deleteInbox(long userId) {
        redis.executeVoid(jedis -> {
            Pipeline pipeline = jedis.pipelined();
            pipeline.del(CacheKeys.feedInbox(userId));
            pipeline.del(CacheKeys.feedInboxFull(userId));
            pipeline.sync();
        });
    }

    /**
     * ② DB 重查（事务回调**只做 DB 只读**，沿 {@code FeedService#getFeed} 范式）：
     * 关注者 ids（DB 口径，不读缓存——与核对 oracle 同源）→ 分页取 contentId。
     *
     * <p>口径与拉模式**同源**：直接复用 {@code ContentDao.findContentIdsByUsers}
     * （{@code is_deleted = 0} + {@code ORDER BY create_time DESC, id DESC} + {@code LIMIT/OFFSET}）。
     * 注意这里是**该用户关注关系的全量读**（与 {@code /feed} 的 {@code getFollowingIds} 同一口径；
     * "全量粉丝读"红线属 T18 的 fanout 方向，此处方向相反且重建本就要求全量快照）；
     * 代价（内存 ∝ 关注规模 × 内容量）登记为 T19 残余窗口，规模策略属三期。
     *
     * @return 该用户收件箱应有的内容 id 序列（可能为空 = 未关注任何人 / 关注者无内容）
     */
    private List<Long> loadContentIds(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                List<Long> followedIds = followDao.getAllFollowedUserIds(conn, userId);
                if (followedIds.isEmpty()) {
                    return Collections.emptyList();
                }
                List<Long> contentIds = new ArrayList<>();
                int offset = 0;
                while (true) {
                    List<Long> page = contentDao.findContentIdsByUsers(conn, followedIds, offset, REBUILD_PAGE_SIZE);
                    contentIds.addAll(page);
                    if (page.size() < REBUILD_PAGE_SIZE) {
                        return contentIds;   // 不足一页 = DB 已到底
                    }
                    offset += page.size();
                }
            } catch (SQLException e) {
                // 该链唯一捕获点（§3.1 附加纪律 2"包装点即源头"）：SEVERE + 栈；只记 userId，不记 id 明细
                LOGGER.log(Level.SEVERE, "收件箱重建 DB 重查失败, userId=" + userId, e);
                throw new ServerException("收件箱重建失败");
            }
        });
    }

    /**
     * ③ ZADD 合并（分批、单连接多 pipeline）+ 全部写完后写**完整态标记**。
     *
     * <p>空集同样要写标记（"空但完整"= 该用户确实没有可看的内容），否则二期会把"未关注任何人"
     * 误判为"收件箱不完整"而回退拉模式（结果相同但多付一次 DB 聚合）。
     *
     * <p>标记 TTL 与收件箱同源（{@code feed.inbox.ttlMinutes}）：整条收件箱的活跃期到期即回收。
     */
    private void writeInbox(long userId, List<Long> contentIds) {
        long ttlSeconds = AppConfig.getFeedInboxTtlSeconds();
        String inboxKey = CacheKeys.feedInbox(userId);
        String fullKey = CacheKeys.feedInboxFull(userId);
        redis.executeVoid(jedis -> {
            for (int from = 0; from < contentIds.size(); from += REBUILD_PAGE_SIZE) {
                int to = Math.min(from + REBUILD_PAGE_SIZE, contentIds.size());
                writeBatch(jedis, inboxKey, contentIds.subList(from, to), ttlSeconds);
            }
            jedis.set(fullKey, CacheKeys.FEED_INBOX_FULL_MARKER_VALUE, SetParams.setParams().ex(ttlSeconds));
        });
    }

    private static void writeBatch(Jedis jedis, String inboxKey, List<Long> batch, long ttlSeconds) {
        Pipeline pipeline = jedis.pipelined();
        for (Long contentId : batch) {
            pipeline.zadd(inboxKey, contentId, String.valueOf(contentId));   // score = contentId（自增单调）
        }
        pipeline.expire(inboxKey, ttlSeconds);      // 与 fanout 同口径：写入即滑动续期
        pipeline.sync();
    }
}
