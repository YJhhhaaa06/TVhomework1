package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.CacheStats;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.ZSetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.CacheException;
import com.itheima.follow.service.FollowCache;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 写扩散：把一条新内容写进作者每个粉丝的收件箱（feed1-18 T18）。
 *
 * <p><b>载体与语义</b>（NEEDS 4.0 机制骨架）：{@code feed:inbox:{fanId}} ZSet，成员 = contentId、
 * score = contentId；<b>fanout 永远写</b>——只用幂等 {@code ZADD}，<b>不检查收件箱是否存在、
 * 不 DEL、不写"完整态标记"</b>（完整标记只由重建写，属 T19）。收件箱是**派生副本**，丢了可由
 * 重建（{@code DEL → DB 重查 → ZADD 合并}）自愈，故 Redis 不可用时只降级、不影响业务。
 *
 * <p><b>粉丝列表读法</b>（NEEDS 4.1 N3 红线）：**不新增全量粉丝读**，一律走
 * {@link FollowCache#getFollowerWindow} 的**窗口迭代**（{@code [offset, offset+BATCH)}），
 * 装载量与页大小相关而非粉丝总量（治 U-18 同型隐患）。
 *
 * <p><b>批量写</b>：一轮窗口内所有粉丝在**同一条 Jedis 连接 + 单个 pipeline** 上写完
 * （{@code ZADD + EXPIRE} 各一次/粉丝），避免"逐粉丝各借还一次连接"。
 *
 * <p><b>不抛异常</b>（红线）：粉丝窗口读取失败 / Redis 写失败一律记日志后返回；
 * 调用方是 MQ 消费线程，异常若上抛会转死信（可接受），但"窗口读取失败"属外部依赖抖动，
 * 直接降级比投死信更符合"影子期不放大故障"的取向。
 *
 * <p><b>日志与打点口径</b>：两类降级各记一条 WARNING（"是否需要人介入"= 否，§3.1-②）；
 * 窗口 loader 的 DB 失败源头（{@code FollowCache.loadIds}）已持 SEVERE + 栈，此处只记结论行、**不带栈**
 * （§3.1 附加纪律 2"一次失败只允许一条带堆栈的记录"）；Redis 写失败则是该链唯一捕获点 → **持栈**。
 * 打点：只记 {@code WRITE_FAIL}，dataKey 取本批首个收件箱 key ⇒ 归 **FEED** 域（
 * 一期 FEED 域只在写失败时出现；读侧 HIT/MISS/DEGRADE 属二期切读）。
 */
@Component
public class FeedInboxCache {

    private static final Logger LOGGER = LogUtil.getLogger(FeedInboxCache.class);

    /**
     * 粉丝窗口迭代批量（包内常量，对齐 mq 包"非必要不入配置"口径）：一期发布量低，
     * 该值只影响"每轮 DB 查询 + Redis 往返次数"，不需按环境调参；三期做阈值/活跃策略时再议。
     */
    static final int FANOUT_BATCH = 200;

    private final FollowCache followCache;
    private final RedisAccess redis;
    private final CacheStats stats;

    @InjectConstructor
    public FeedInboxCache(FollowCache followCache, RedisAccess redis, CacheStats stats) {
        this.followCache = followCache;
        this.redis = redis;
        this.stats = stats;
    }

    /**
     * 写扩散一条内容：向作者的所有粉丝收件箱各追加 {@code contentId}。
     *
     * <p>幂等（ZADD；同一内容重复投递无副作用）。任何失败都不抛出。
     *
     * @param contentId 新内容 id（收件箱成员 / ZSet score）
     * @param authorId  作者 id（粉丝列表来源）
     */
    public void fanout(long contentId, long authorId) {
        long ttlSeconds = AppConfig.getFeedInboxTtlSeconds();
        long offset = 0L;
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
            if (!writeBatch(contentId, fanIds, ttlSeconds)) {
                // 首批写失败即短路本次 fanout：Redis 已不可用（熔断开启后各批都是快速失败），
                // 继续遍历只会把"窗口 DB 查 + 降级日志"按批数各刷一遍（放大 ∝ 粉丝数）；
                // 未写到的粉丝由 T19 重建兜底，不影响对外正确性（一期本就无人读收件箱）。
                return;
            }
            if (fanIds.size() < FANOUT_BATCH) {
                // 不足一批 = DB 已到底（ZSetCache 窗口装载的既有终止口径，不依赖 total，防计数漂移）
                return;
            }
            offset += fanIds.size();
        }
    }

    /**
     * 单条连接 + 单次 pipeline 写完一批粉丝的收件箱（ZADD 幂等 + EXPIRE 滑动续期）。
     *
     * @return true = 本批已写入；false = Redis 写失败（已记 WARNING + WRITE_FAIL 打点，调用方应中止本次 fanout）
     */
    private boolean writeBatch(long contentId, List<Long> fanIds, long ttlSeconds) {
        String anyKey = CacheKeys.feedInbox(fanIds.get(0));
        try {
            redis.executeVoid(jedis -> writeAll(jedis, contentId, fanIds, ttlSeconds));
            return true;
        } catch (CacheException e) {
            // Redis 写失败：该链唯一捕获点 → 持栈；打点一次（不逐粉丝刷），dataKey 归 FEED 域
            LOGGER.log(Level.WARNING, "写扩散收件箱写入失败（降级，读路径不受影响）, contentId=" + contentId
                    + ", fanCount=" + fanIds.size(), e);
            stats.record(CacheStats.Event.WRITE_FAIL, anyKey);
            return false;
        }
    }

    private static void writeAll(Jedis jedis, long contentId, List<Long> fanIds, long ttlSeconds) {
        String member = String.valueOf(contentId);
        Pipeline pipeline = jedis.pipelined();
        for (Long fanId : fanIds) {
            String inboxKey = CacheKeys.feedInbox(fanId);
            pipeline.zadd(inboxKey, contentId, member);   // score = contentId（自增单调 → 降序即内容倒序）
            pipeline.expire(inboxKey, ttlSeconds);         // 写入即续期：只要有关注对象在发，收件箱不老化
        }
        pipeline.sync();
    }
}
