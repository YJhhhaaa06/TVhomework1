package com.itheima.cache;

import com.itheima.config.AppConfig;
import com.itheima.ioc.annotation.Component;
import com.itheima.util.LogUtil;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis 全局熔断器（T1 cache-01，治 U-10/N1：Redis 不可用时"快速失败"）。
 *
 * <p>状态机：CLOSED →（连续失败 ≥ 阈值）OPEN →（冷却期满，首个到达请求成为唯一探针）
 * HALF_OPEN → 探针成功 CLOSED / 探针失败回 OPEN（重置冷却）。
 *
 * <p><b>粒度决策（执行定稿，已回写任务清单）</b>：全局单熔断——单 Redis 实例宕机影响所有域，
 * 按域熔断只增加探针流量无收益。
 *
 * <p><b>失败口径（执行定稿，已回写任务清单）</b>：从 {@code RedisAccess.execute} 冒出的
 * {@code CacheException} 计一次失败——RedisAccess 产生的包装异常均为 Redis 起源
 * （取连接失败 / 命令执行失败 / 归还连接失败）；回调内自抛 CacheException 极罕见，
 * 计数偏差无害（最坏只是提前降级，不违反"缓存失败不导致业务失败"）。
 *
 * <p><b>恢复探测</b>：OPEN 冷却期满后，{@link #tryAcquire()} 以 CAS 放行<b>唯一</b>探针请求
 * （其余请求仍被拒绝、立即降级）；探针成功 → CLOSED 恢复正常缓存路径，探针失败 → 重开并重置冷却。
 *
 * <p>线程安全：状态用 {@link AtomicInteger}、冷却起点用 volatile，无锁 CAS；
 * 不引入任何第三方依赖。
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger LOGGER = LogUtil.getLogger(RedisCircuitBreaker.class);

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;

    private final int failureThreshold;
    private final long cooldownMillis;

    /** 当前状态（CLOSED/OPEN/HALF_OPEN）。 */
    private final AtomicInteger state = new AtomicInteger(CLOSED);

    /** 连续失败计数（recordSuccess 清零；语义 = "连续"，一次成功即证明 Redis 可用）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断开启时刻（毫秒），恢复冷却计时起点。 */
    private volatile long openedAtMillis;

    /** 默认构造（IoC 用）：阈值与冷却从 AppConfig 读取（redis.breaker.*）。 */
    public RedisCircuitBreaker() {
        this(AppConfig.getRedisBreakerFailureThreshold(),
                AppConfig.getRedisBreakerCooldownMillis());
    }

    /** 包级可见：供测试注入小阈值/短冷却，快速验证状态机。 */
    RedisCircuitBreaker(int failureThreshold, long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * 取得一次 Redis 访问许可。
     *
     * <ul>
     *   <li>CLOSED → 放行；</li>
     *   <li>OPEN 且冷却未到 → 拒绝（调用方立即降级，不访问 Redis）；</li>
     *   <li>OPEN 且冷却期满 → CAS OPEN→HALF_OPEN，成功者作为<b>唯一</b>探针放行；</li>
     *   <li>HALF_OPEN → 拒绝（已有探针在飞，其余不重复探测）。</li>
     * </ul>
     *
     * @return true = 允许访问 Redis；false = 熔断中，调用方应立即降级走 DB
     */
    public boolean tryAcquire() {
        while (true) {
            int s = state.get();
            if (s == CLOSED) {
                return true;
            }
            if (s == HALF_OPEN) {
                return false;
            }
            // OPEN：冷却未到 → 拒绝
            if (System.currentTimeMillis() - openedAtMillis < cooldownMillis) {
                return false;
            }
            // 冷却期满：CAS 成功者成为唯一探针
            if (state.compareAndSet(OPEN, HALF_OPEN)) {
                LOGGER.log(Level.INFO, "Redis 熔断冷却期满，放行单个探针请求探测恢复");
                return true;
            }
            // CAS 失败（其他线程已转换状态）→ 重读状态
        }
    }

    /** 记一次 Redis 访问成功：清零连续失败计数，恢复 CLOSED（探针成功即恢复正常路径）。 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        int prev = state.getAndSet(CLOSED);
        if (prev != CLOSED) {
            LOGGER.log(Level.INFO, "Redis 熔断恢复，回到正常缓存路径");
        }
    }

    /** 记一次 Redis 访问失败：HALF_OPEN 探针失败 → 重开（重置冷却）；CLOSED → 计数，达阈值即熔断。 */
    public void recordFailure() {
        if (state.get() == HALF_OPEN) {
            // 探针失败重开："先写数据、后置标志"——并发 tryAcquire 经 state volatile 读观测到
            // OPEN 后，按 happens-before 链必读到本次新冷却起点，不会用旧起点误判"冷却期满"
            // 而多放行探针；CAS 失败（并发 recordSuccess 已闭合）则落入下方计数分支，无害
            openedAtMillis = System.currentTimeMillis();
            if (state.compareAndSet(HALF_OPEN, OPEN)) {
                LOGGER.log(Level.WARNING, "Redis 熔断探针失败，重新开启熔断（冷却重置）");
                return;
            }
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            // 竞态下可能已被置 OPEN（compareAndSet 失败无害，openedAt 已刷新）
            openedAtMillis = System.currentTimeMillis();
            if (state.compareAndSet(CLOSED, OPEN)) {
                LOGGER.log(Level.WARNING, "Redis 连续失败 " + consecutiveFailures.get()
                        + " 次，熔断开启：缓存请求将快速失败 " + (cooldownMillis / 1000) + "s 后探测恢复");
            }
        }
    }

    /** 当前状态明文（观测/测试用）。 */
    String stateName() {
        int s = state.get();
        switch (s) {
            case OPEN:
                return "OPEN";
            case HALF_OPEN:
                return "HALF_OPEN";
            default:
                return "CLOSED";
        }
    }
}
