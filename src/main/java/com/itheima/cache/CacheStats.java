package com.itheima.cache;

import com.itheima.ioc.annotation.Component;
import com.itheima.util.LogUtil;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 缓存观测统计组件（T7 二期新增，NEEDS 4.14，治 H14 无观测能力）。
 *
 * <p>六类事件计数（AtomicLong 无锁）+ 按数据 key 前缀分域分桶（域解析收敛于
 * {@link CacheKeys#domainOf}，key 生成与解析同源）+ **惰性日志输出**——每 N 次记录
 * 顺带输出一次各域摘要（不引入定时器、不新增端点；N=1000 为 T7 执行定稿默认值）。
 *
 * <p>埋点位置：{@link CacheAside} 自动打点（内容/评论/点赞计数 JSON 路径的三态读、降级、
 * 写失败）+ {@code LikeCacheService}/{@code FollowCache} 原生 Set 三态读分支（含批量
 * pipeline 路径）手动打点。
 *
 * <p>红线：统计**不影响主链路**——{@link #record} 自身异常一律吞掉记 WARNING，不抛出；
 * 不打任何新 Redis 命令、不改缓存读写语义（三态判断顺序/空标记/DEL 降级路径一概不动）。
 */
@Component
public class CacheStats {

    private static final Logger LOGGER = LogUtil.getLogger(CacheStats.class);

    /** 六类事件（NEEDS 4.14：hitData / hitEmpty / miss / loadCount / degradeCount / writeFailCount）。
     * <p>LOAD=**DB 装载尝试次数**：仅在真实 loader 执行处打（singleFlight 回调与降级 DB 兜底路径统一口径），
     * 单飞并发去重后同一 key 一次 miss 只计一次；loader 抛异常（装载失败）也计入——语义为"尝试装载"。 */
    public enum Event {
        HIT_DATA, HIT_EMPTY, MISS, LOAD, DEGRADE, WRITE_FAIL
    }

    /** 惰性日志输出阈值：每 N 次记录输出一次各域摘要（T7 执行定稿，不引入定时器）。 */
    private static final int DEFAULT_LOG_INTERVAL = 1000;

    private final int logInterval;
    private final AtomicLong accessCount = new AtomicLong();

    /** counters[domain.ordinal()][event.ordinal()]——固定数组 + AtomicLong，无锁无扩容，热路径零分配。 */
    private final AtomicLong[][] counters;

    /** 默认构造（IoC 用）；日志阈值取 DEFAULT_LOG_INTERVAL。 */
    public CacheStats() {
        this(DEFAULT_LOG_INTERVAL);
    }

    /** 包级可见：供测试用小 N 触发惰性输出验证。 */
    CacheStats(int logInterval) {
        this.logInterval = logInterval;
        this.counters = new AtomicLong[CacheDomain.values().length][Event.values().length];
        for (int i = 0; i < counters.length; i++) {
            for (int j = 0; j < counters[i].length; j++) {
                counters[i][j] = new AtomicLong();
            }
        }
    }

    /**
     * 记录一次缓存事件（按数据 key 归域）。
     *
     * <p>打点自身异常吞掉记日志（红线），不抛给主链路；触发到阈值时顺带输出各域摘要。
     *
     * @param event   事件类型
     * @param dataKey 缓存数据 key（空标记 key 由 domainOf 自动解包归域）
     */
    public void record(Event event, String dataKey) {
        try {
            counters[CacheKeys.domainOf(dataKey).ordinal()][event.ordinal()].incrementAndGet();
            long total = accessCount.incrementAndGet();
            if (logInterval > 0 && total % logInterval == 0) {
                logSummary();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "缓存统计打点异常（忽略，不影响主链路）", e);
        }
    }

    /** 观测明文：某域某事件的累计计数（测试与未来 T9 取参用）。 */
    public long count(CacheDomain domain, Event event) {
        return counters[domain.ordinal()][event.ordinal()].get();
    }

    /** 统计累计记录数。 */
    public long totalAccesses() {
        return accessCount.get();
    }

    /** 各域六事件摘要（INFO 单行，对齐 LogUtil 日志风格）。 */
    private void logSummary() {
        StringBuilder sb = new StringBuilder("CacheStats 摘要: total=").append(accessCount.get());
        for (CacheDomain domain : CacheDomain.values()) {
            sb.append(' ').append(domain.name().toLowerCase()).append('{');
            boolean first = true;
            for (Event event : Event.values()) {
                if (!first) {
                    sb.append(' ');
                }
                first = false;
                sb.append(eventKey(event)).append('=')
                        .append(counters[domain.ordinal()][event.ordinal()].get());
            }
            sb.append('}');
        }
        LOGGER.log(Level.INFO, sb.toString());
    }

    private static String eventKey(Event event) {
        switch (event) {
            case HIT_DATA:
                return "hitData";
            case HIT_EMPTY:
                return "hitEmpty";
            case MISS:
                return "miss";
            case LOAD:
                return "load";
            case DEGRADE:
                return "degrade";
            case WRITE_FAIL:
                return "writeFail";
            default:
                return event.name().toLowerCase();
        }
    }
}