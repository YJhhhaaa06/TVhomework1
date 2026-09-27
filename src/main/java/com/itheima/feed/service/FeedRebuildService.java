package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.follow.dao.FollowDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import redis.clients.jedis.params.SetParams;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱重建（窗口化，feed2-22 T22；替代 feed1-19 T19 的全量重算 + Redis 三步）。
 *
 * <p><b>职责</b>：关注 / 取关后重算该用户收件箱的**有界窗口**，产物 = DB 真相表
 * {@code feed_inbox} 的整窗替换 + 同步状态 {@code feed_inbox_sync} 落库；Redis
 * （{@code feed:inbox:{id}}）**只失效、不写**（单写 DB 真相 + 写后失效，与 fanout 同范式）。
 * 窗口口径（NEEDS 4.0 机制拍板）= **每关注作者最近 K 条** → 归并去重 → contentId 降序 →
 * **裁剪到 C**（K = {@link AppConfig#getFeedInboxWindowPerAuthor()}，C =
 * {@link AppConfig#getFeedInboxWindowMax()}）；这把 T19 的"全量重算"（内存 ∝ 关注规模 ×
 * 内容量）改为**有界**（内存 ∝ 关注数 × K），并保证长尾作者不被高产作者挤空。
 *
 * <p><b>步骤</b>（① 取锁 → ② 读关注集 → ③ 排除大V → ④ 单事务替换窗口 + 写同步状态 →
 * ⑤ 提交后失效缓存 → ⑥ 释放锁）：
 * <ol>
 *   <li>② 读关注集：{@link FollowDao#getAllFollowedUserIds}（DB 口径，**不读缓存**——与拉模式
 *       / 核对 oracle 同源）；</li>
 *   <li>③ 排除大V：逐作者走 {@link FeedBigVRouter#isBigV}（**判定单点**，与 fanout / 读三处同源）
 *       ——fanout 对大V作者跳过写扩散（其内容由"大V发件箱"读时拉，T23），重建若收录则同一内容
 *       两路都出 ⇒ 排除即"不与发件箱重复"；</li>
 *   <li>④ 单事务：<b>先清</b>（{@code DELETE FROM feed_inbox WHERE user_id=?}）→ 逐作者最近 K 重查
 *       （{@link ContentDao#findRecentContentIdsByUsers}，按 {@value #AUTHOR_BATCH} 一批）→ 归并 /
 *       去重 / 降序 / 裁剪 → {@code INSERT IGNORE}（空窗不发）→ upsert {@code feed_inbox_sync}
 *       （**空窗也写** = "空但已同步"）；</li>
 *   <li>⑤ 提交后 {@code DEL} 收件箱读缓存三件套（数据 key + {@code empty:} + {@code partial:}）。</li>
 * </ol>
 *
 * <p><b>顺序论证（替代 T19 的"先清后建 + ZADD 可交换"；红线重论证）</b>：新论证锚在
 * {@code feed_inbox}（**DB 真相**）层，依赖两条不变量——① 重建事务内**唯一一次删除（④ 的 DELETE）
 * 之后不再有任何删除动作**，窗口写入用 {@code INSERT IGNORE}（幂等、不删）；② 并发 fanout 也是
 * {@code INSERT IGNORE} 追加，不删。逐情形看——某条内容的 fanout 行若在本次 DELETE **之前**提交，
 * 则该行虽被删，但其 {@code content} 行必已提交于 DELETE 之前，而窗口快照读（一致性读）发生在
 * DELETE **之后**（本方法让 DML 先于首个普通 SELECT）⇒ **必被本次快照收录**（仍在该作者最近 K、
 * 非大V、未软删时）；若在 DELETE **之后**到达，则它落在"已清空区"且此后无删除 ⇒ **保留**。
 * 即 **最终窗口 ⊇ 本次快照，多出的成员只来自 DELETE 之后到达的 fanout**——**只多不丢**。
 * 故 **DEL（DB 清空）必须早于窗口重查、且其后不得再删**（顺序不可交换：先查后清会在
 * "查完 → 清"窗口里丢掉 fanout 增量）。
 *
 * <p><b>缓存失效位置（与 T19 的差异）</b>：T19 先 DEL 缓存再重建缓存；本类改为**DB 事务提交后**
 * 才失效——DB 是真相源，"提交后失效"避免"DEL 与提交之间读者回源旧窗口并回填"的更宽竞态，
 * 且与 fanout"先落库后失效"同范式。彻底不写缓存 ⇒ 无"写透"，与反面清单一致
 * （命中率不承诺；读 miss 回源属 T23）。
 *
 * <p><b>同步状态</b>：{@code feed_inbox_sync} 与窗口替换**同一事务**写入（否则"同步态已写但窗口
 * 回滚"会让读侧误信陈旧窗口）；**存在即已同步**，{@code sync_time} 只作诊断（不参与判定）。
 * 一期 Redis 完整态标记 {@code feed:inbox:full:{id}} 随本任务**退役**。
 *
 * <p><b>并发去重</b>：{@code SET NX EX} + Lua CAS 释放。锁只是**去重优化**，不承担正确性：
 * TTL（{@value #REBUILD_LOCK_TTL_SECONDS}s）到点后若真有并发重建交叉执行，两次都是同一份
 * {@code content} 真相的窗口快照（`INSERT IGNORE` 幂等）⇒ **并集、只多不丢**，故不续期、
 * 也不要求锁覆盖全程。
 *
 * <p><b>失败面（口径不变）</b>：Redis / DB 失败一律**降级吞掉并 ACK**（**不抛给消费容器转死信**；
 * 一期无重试，漏重建由下次关注 / 取关或二期读触发兜底）——只有**载荷非法**才在消费者侧抛出。
 *
 * <p><b>日志口径（§3.1）</b>：一次失败**只允许一条带堆栈记录**——缓存失效（本链唯一 Redis 捕获点）
 * 与取锁失败由本类持栈；DB 阶段失败由事务回调持 SEVERE + 栈（源头），本类只补结论行、不带栈；
 * 锁释放失败属"非影响性失败"（TTL 兜底）故只记 WARNING、不重复带栈。循环体内不记 INFO；
 * 成功路径不刷日志；大V排除属常态路由，不刷日志。
 *
 * <p><b>残余窗口（已登记）</b>：① "只多不丢"是**相对本次有界快照**而言的，且依赖 InnoDB"一致性读的
 * read view 建立在首个普通 SELECT 时点"这一实现语义（排序前提已用只读 SQL 实测，但**纯应用层无法
 * 100% 证明**）——该语义不成立时的偏差由下次关注 / 取关重建或二期读态兜底收敛；**注意**：被本次
 * DELETE 清掉且未被重查收录的 fanout 行，只可能是"已落在窗口外（超出该作者最近 K / 超总上限 C）/
 * 已软删 / 已升为大V"的内容 ⇒ 属**有界窗口的设计语义**，不是丢失；② 表侧非严格有界（fanout 只追加、
 * 不裁剪，两次重建之间可超 C）——属"容量裁剪弱化、表侧保留策略归三期"，读侧有界归 T23；
 * ③ 大V判定**逐作者串行**（∝ 关注数；Redis 故障时还各记一条 WARNING），happy-path 延迟同样 ∝ 关注数
 * ——批量 / 短路归三期，同 T21 已登记的"窗口读降级日志放大"口径；④ 大V判定在**写侧 fail-open** 时
 * 会把大V内容收进窗口，且**不会被主动清除**（须待下次关注 / 取关重建才清），期间表侧残留、对用户
 * 可见的重复由读侧按 contentId 去重兜底；⑤ 重建的 `DELETE` 会对该 user 的 `uk_user_content` 索引
 * 区间持范围锁至提交 ⇒ 与同 user 的并发 fanout `INSERT IGNORE` 互相阻塞（极端交叉下可能死锁），由
 * InnoDB 死锁检测 + 两侧"降级不抛"自愈兜底（重建事务体短、量级小）。
 */
@Component
public class FeedRebuildService {

    private static final Logger LOGGER = LogUtil.getLogger(FeedRebuildService.class);

    /** 重建锁 TTL（秒，包内常量，沿 mq 包"非必要不入配置"口径）：仅兜"进程猝死未释放"，到点即视为可重入。 */
    static final long REBUILD_LOCK_TTL_SECONDS = 60L;

    /**
     * 窗口重查的**作者批量**（包内常量）：每批一条 `UNION ALL` 语句（一趟往返），
     * 用于约束 SQL 长度与绑定参数个数；与批量页大小同量级，不需按环境调参。
     */
    static final int AUTHOR_BATCH = 50;

    /** 锁释放：CAS（值等于自己的 token 才删）——避免误删他人已获得的锁。 */
    static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final FollowDao followDao;
    private final ContentDao contentDao;
    private final TransactionTemplate transactionTemplate;
    private final FeedInboxDao feedInboxDao;
    private final FeedBigVRouter bigVRouter;
    private final RedisAccess redis;
    private final CacheStats stats;

    @InjectConstructor
    public FeedRebuildService(FollowDao followDao, ContentDao contentDao,
                              TransactionTemplate transactionTemplate, FeedInboxDao feedInboxDao,
                              FeedBigVRouter bigVRouter, RedisAccess redis, CacheStats stats) {
        this.followDao = followDao;
        this.contentDao = contentDao;
        this.transactionTemplate = transactionTemplate;
        this.feedInboxDao = feedInboxDao;
        this.bigVRouter = bigVRouter;
        this.redis = redis;
        this.stats = stats;
    }

    /**
     * 重建 {@code userId} 的收件箱窗口（= 每关注作者最近 K → 归并裁剪 C，落 DB 真相 + 同步状态，
     * 并失效其 Redis 读缓存）。**任何失败都不抛出。**
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
            List<Long> authors = excludeBigV(loadFollowedUserIds(userId));   // ② + ③
            replaceWindow(userId, authors);                                  // ④ 单事务
            invalidateCache(userId);                                         // ⑤ 提交后失效
        } catch (CacheException e) {
            // 缓存失效失败：该链唯一 Redis 捕获点 → 持栈；降级不抛（DB 窗口与同步状态已提交）
            LOGGER.log(Level.WARNING, "收件箱重建缓存失效失败（读自愈兜底，DB 窗口不受影响）, userId=" + userId, e);
            stats.record(CacheStats.Event.WRITE_FAIL, CacheKeys.feedInbox(userId));
        } catch (ServerException e) {
            // DB 阶段失败：源头（事务回调）已持 SEVERE + 栈 → 此处只记结论行、不带栈
            LOGGER.log(Level.WARNING, "收件箱重建中止（DB 窗口重算失败，降级）, userId=" + userId);
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

    // ==================== 步骤 ② / ③ ====================

    /**
     * ② 读关注集（事务回调**只做 DB 只读**，沿 {@code FeedService#getFeed} 范式）：DB 口径，
     * 不读缓存（与核对 oracle 同源；"全量粉丝读"红线属 fanout 方向，此处方向相反且重建本就
     * 要求完整的关注集——窗口成本 ∝ 关注数 × K）。
     *
     * <p>独立一次连接借还：不在持连接的情况下做后续的缓存 I/O（大V判定）。
     */
    private List<Long> loadFollowedUserIds(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return followDao.getAllFollowedUserIds(conn, userId);
            } catch (SQLException e) {
                // 该链唯一捕获点（§3.1 附加纪律 2"包装点即源头"）：SEVERE + 栈；只记 userId，不记 id 明细
                LOGGER.log(Level.SEVERE, "收件箱重建关注列表查询失败, userId=" + userId, e);
                throw new ServerException("收件箱重建失败");
            }
        });
    }

    /**
     * ③ 排除大V作者（判定单点 {@link FeedBigVRouter#isBigV}，与 fanout / 读同源）。
     *
     * <p>为什么排除：fanout 对大V作者**跳过写扩散**（其内容由"大V发件箱"读时拉，T23），
     * 重建若收录 ⇒ 同一内容在"收件箱窗口 ∪ 大V发件箱"两路都出（仅靠读侧按 contentId 去重兜底）
     * ⇒ 排除即"不与发件箱重复"。
     *
     * <p>失败取向：{@code isBigV} 内部对粉丝数读取失败已 **fail-open 返回 false**（记 WARNING 结论行）
     * ⇒ 此处按普通作者收录（"宁可多收录"，与 fanout"宁可多写"同向，残影由读侧去重兜底）。
     * **配置非法**（{@code feed.bigv.userIds} 解析失败）会抛 {@code IllegalArgumentException} →
     * 由调用链的 RuntimeException 兜底记录（fail-fast 语义不吞）。
     */
    private List<Long> excludeBigV(List<Long> followedIds) {
        List<Long> authors = new ArrayList<>(followedIds.size());
        for (Long authorId : followedIds) {
            if (!bigVRouter.isBigV(authorId)) {
                authors.add(authorId);
            }
        }
        return authors;
    }

    // ==================== 步骤 ④ ====================

    /**
     * ④ 单事务「替换窗口 + 写同步状态」（红线顺序：**先清 → 窗口重查 → 归并裁剪 → 写同步状态**；
     * 事务内其后不再有任何删除动作，见类注释的顺序论证）。
     *
     * <p>失败：{@code SQLException} 在本链唯一捕获点（事务回调）记 SEVERE + 栈后包
     * {@link ServerException}；事务回滚 ⇒ 窗口与同步状态同时不生效（无"窗口新而同步态旧"的错位），
     * 收件箱退化为"未同步"态 ⇒ 二期读回退拉模式，对外正确性不依赖推。
     */
    private void replaceWindow(long userId, List<Long> authors) {
        transactionTemplate.execute(conn -> {
            try {
                feedInboxDao.deleteByUser(conn, userId);            // ④a 先清（事务内唯一删除）
                List<Long> raw = new ArrayList<>();
                for (int from = 0; from < authors.size(); from += AUTHOR_BATCH) {
                    int to = Math.min(from + AUTHOR_BATCH, authors.size());
                    raw.addAll(contentDao.findRecentContentIdsByUsers(conn, authors.subList(from, to),
                            AppConfig.getFeedInboxWindowPerAuthor()));   // ④b 逐作者最近 K
                }
                List<Long> window = mergeDedupSortTrim(raw, AppConfig.getFeedInboxWindowMax());
                if (!window.isEmpty()) {
                    feedInboxDao.insertBatch(conn, userId, window);   // ④d 空窗不发 SQL
                }
                feedInboxDao.upsertSync(conn, userId);                // ④e 空窗也写（"空但已同步"）
                return null;                                          // 无返回值需求（事务只承载副作用）
            } catch (SQLException e) {
                // 该链唯一捕获点（包装点即源头）：SEVERE + 栈；只记 userId 与规模
                LOGGER.log(Level.SEVERE, "收件箱重建 DB 窗口重算失败, userId=" + userId
                        + ", authorCount=" + authors.size(), e);
                throw new ServerException("收件箱重建失败");
            }
        });
    }

    /**
     * ④c 归并 → 去重 → contentId 降序 → 裁剪到 C。
     *
     * <p>排序口径 = **contentId**（{@code content.id} 自增单调 ⇒ id 越大越新；与
     * {@code feed_inbox} 不存时间字段、"排序 / 归并 / 裁剪全按 contentId"的收件箱层口径同源）。
     * 内存 O(关注数 × K)、时间 O(N·K log(N·K))——规模增长后可换有界最小堆，本期取简单实现
     * （去重用无序 {@link HashSet}：顺序由随后的排序决定，无需保插入序）。
     */
    private static List<Long> mergeDedupSortTrim(List<Long> raw, int windowMax) {
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinct = new ArrayList<>(new HashSet<>(raw));
        distinct.sort(Collections.reverseOrder());
        if (distinct.size() <= windowMax) {
            return distinct;
        }
        return new ArrayList<>(distinct.subList(0, windowMax));
    }

    // ==================== 步骤 ⑤ ====================

    /**
     * ⑤ 提交后失效收件箱读缓存（**三件套**：数据 key + {@code empty:} + {@code partial:}，
     * 见 {@link CacheKeys#feedInboxCacheKeys(long)}）。单命令 `DEL` 多键，一趟往返。
     *
     * <p>失败由调用方按"唯一捕获点"记 WARNING 持栈 + `WRITE_FAIL` 打点；不重试
     * （残留缓存由 TTL / 下次写失效 / 重建兜底）。
     */
    private void invalidateCache(long userId) {
        redis.executeVoid(jedis -> jedis.del(CacheKeys.feedInboxCacheKeys(userId)));
    }
}
