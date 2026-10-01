package com.itheima.mq;

import com.itheima.config.AppConfig;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogContext;
import com.itheima.util.LogUtil;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MQ 投递线程池（feed3-T31，治 NEEDS 4.1 {@code N8}）：把「publish + waitForConfirms」从 Web 请求线程
 * 挪到**专用后台线程**——发布 / 关注 / 取关接口的 RT 不再受 MQ 慢 / 不可达影响（确认等待最长 5s 的
 * 既有痛点见 {@link MqPublisher}）。
 *
 * <p><b>形态（T31 拍板 = 专用投递线程池 + 有界队列，JDK 自带构件、零新依赖）</b>：
 * {@code ThreadPoolExecutor(1 worker, ArrayBlockingQueue(capacity))}。
 *
 * <ul>
 *   <li><b>单 worker 是设计而非省略</b>：{@link MqPublisher} 一期拍板 = 单 channel + confirmLock
 *       串行「publish + 确认」，真实投递本就是单点串行——多线程 / 多虚拟线程只会在锁上排队，
 *       无吞吐收益（吞吐面 = 多 channel，已移交池档 {@code U-34}，本期红线"不承诺吞吐提升"）；
 *       单 worker 还天然保留**提交序**（发布 / 重建消息的全局顺序不因异步化而打乱）。</li>
 *   <li><b>有界队列 = 背压</b>：这是本类存在的核心理由——虚拟线程 per-task 形态无队列、无拒绝，
 *       MQ 慢时任务在内存无界堆积（每条持 payload）。队列满 ⇒ 沿用默认 {@code AbortPolicy}（拒绝抛
 *       {@link RejectedExecutionException}），{@link #submit} 捕获后**丢弃 + 记 WARNING**（降级，
 *       不影响业务；丢失面 = NEEDS 4.3 残余②既有口径，由收件箱重建兜底）。
 *       **不得用 {@code CallerRunsPolicy}**——那会把确认等待拉回 Web 线程，RT 解耦白做；
 *       也不得用"静默吞"型自定义 handler——{@code execute()} 会正常返回，提交方无从得知被拒。</li>
 *   <li><b>关停有界 drain</b>：{@link #destroy()} 先停收新任务、再在上界内等 worker 投完队列存量；
 *       超时 {@code shutdownNow()} 丢弃剩余并记 WARNING——"队列未投完即退出"的可接受性 =
 *       NEEDS 4.3 残余②。上界存在的原因：不得挂住 Tomcat 关停（T31 红线）。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：{@code @Component} + {@code Disposable}（无 init——线程池惰性起线程，不阻断启动）。
 * {@code IocContainer.shutdown()} 对 Bean 的销毁**无顺序保证**：若 {@code MqConnectionManager} 先关连接，
 * drain 中的投递会走既有"不可用 fast-fail"降级（不访问 broker、不刷日志）——此情形下队列存量丢失
 * **仍属残余②口径**，drain 本身始终有界。守护线程保证即使极端情况下也不拖住 JVM 退出。
 *
 * <p><b>线程与日志纪律（LOG_CONVENTION §四"非 Web 线程"两条）</b>：
 * 提交时经 {@link LogContext#wrap(Runnable)} 捕获/恢复 reqId（异步线程日志与请求链同 {@code req=}
 * 串联、池化线程不残留）；**任务体必须自带 try/catch 根捕获**（本类是通用构件、不吞业务异常——
 * 两个 Notifier 的任务体各自根捕获 SEVERE + 栈，是该链唯一捕获点）。
 *
 * <p><b>降级总口径（与 T17/T18 一致）</b>：本类任何路径都不向业务线程抛异常——队列满 / 已关停 /
 * 提交意外失败一律丢弃并记日志；真实投递的失败语义仍由 {@link MqPublisher}（绝不抛）与
 * Notifier（根捕获）持有，本类不重复记、不改变既有失败链。
 */
@Component
public class MqDeliveryDispatcher implements Disposable {

    private static final Logger LOGGER = LogUtil.getLogger(MqDeliveryDispatcher.class);

    /** 投递 worker 线程名（对齐 {@code mq-consumer} 命名族；单线程无需序号）。 */
    private static final String WORKER_THREAD_NAME = "mq-delivery";

    private final ThreadPoolExecutor executor;
    private final long drainTimeoutMillis;

    /** 关停标志：{@link #destroy()} 幂等（IoC 可能与测试各自触发一次）。 */
    private volatile boolean shuttingDown;

    /** IoC 注入构造：队列容量与 drain 上界取自 {@code feed.delivery.*}（语义校验在 AppConfig）。 */
    @InjectConstructor
    public MqDeliveryDispatcher() {
        this(AppConfig.getFeedDeliveryQueueCapacity(), AppConfig.getFeedDeliveryDrainTimeoutMillis());
    }

    /**
     * 受控构造（测试 / 特殊部署）：显式指定队列容量与关停 drain 上界。
     *
     * @throws IllegalArgumentException 容量 / drain 上界非正——非正分别导致投递永远无法入队、
     *                                  关停等待无上界，宁可构造即拒（对齐 {@code feed.fanout.batch} 口径）
     */
    public MqDeliveryDispatcher(int queueCapacity, long drainTimeoutMillis) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("feed.delivery.queueCapacity 必须为正数: " + queueCapacity);
        }
        if (drainTimeoutMillis <= 0) {
            throw new IllegalArgumentException("feed.delivery.drainTimeoutMillis 必须为正数: " + drainTimeoutMillis);
        }
        this.drainTimeoutMillis = drainTimeoutMillis;
        // 拒绝策略沿用默认 AbortPolicy：拒绝时抛 RejectedExecutionException，由 submit() 捕获后
        // 记 WARNING（唯一日志点）并返回 false——不用"静默吞"型 handler，提交方必须能得知被拒
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                newWorkerFactory());
    }

    /**
     * 提交一条投递任务（**非阻塞、绝不抛**，提交方是 Web 请求线程）。
     *
     * <p>语义：入队成功 = 本方法职责结束，真实投递由 worker 异步完成（发布线程不再等 confirm）；
     * 队列满 / 已关停 ⇒ 任务被丢弃（记 WARNING 后返回 {@code false}），失败不影响业务
     * （与既有"投递失败只降级"同口径）。
     *
     * <p><b>拒绝判定机制</b>：线程池沿用默认 {@code AbortPolicy}——拒绝时抛
     * {@link RejectedExecutionException}，在**本方法内捕获**并记日志（**拒绝日志唯一落点**；
     * 若改用不抛异常的拒绝策略，{@code execute()} 会正常返回、提交方无从得知被拒）。
     *
     * @param task 投递任务体（**必须自带 try/catch 根捕获**，见类注释的线程与日志纪律；
     *             将经 {@link LogContext#wrap(Runnable)} 包装，reqId 在提交线程捕获）
     * @param desc 一句任务描述（进丢弃日志，便于定位丢的是哪条：如 {@code push contentId=42}）
     * @return {@code true} = 已入队；{@code false} = 队列满 / 已关停被丢弃
     */
    public boolean submit(Runnable task, String desc) {
        try {
            executor.execute(LogContext.wrap(task));
            return true;
        } catch (RejectedExecutionException e) {
            // 队列满 / 已关停是两条不同的降级面：分开设辞，便于运维一眼定位
            if (executor.isShutdown()) {
                LOGGER.warning("投递线程池已关停，任务未提交（降级，不影响业务）: " + desc);
            } else {
                LOGGER.warning("投递队列已满，任务被丢弃（降级，不影响业务）: " + desc);
            }
            return false;
        } catch (RuntimeException e) {
            // 兜底：其余意外（含 LogContext.wrap 自身）——任何情况下不得穿透到业务线程
            LOGGER.log(Level.WARNING, "投递任务提交失败（已丢弃，不影响业务）: " + desc, e);
            return false;
        }
    }

    /** 关停：停收新任务 → 上界内 drain 队列存量 → 超时丢弃剩余；每步不外抛，不挂住 Tomcat 关停。 */
    @Override
    public void destroy() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        executor.shutdown();
        boolean drained = false;
        try {
            drained = executor.awaitTermination(drainTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!drained) {
            List<Runnable> remaining = executor.shutdownNow();
            LOGGER.warning("投递队列在 " + drainTimeoutMillis + "ms drain 上界内未投完，剩余 "
                    + remaining.size() + " 条未投递即退出（关停期丢失 = NEEDS 4.3 残余②已登记口径，重建兜底）");
        }
        // 措辞 = "已处理完"而非"已投完"：drained=true 仅代表队列任务被 worker 跑完，单条投递的
        // 成败由 MqPublisher 既有降级口径持有（IoC destroy 无序，连接可能已先关 → fast-fail 静默丢失）
        LOGGER.log(Level.INFO, "MQ 投递线程池已关闭（drain 上界 " + drainTimeoutMillis + "ms，"
                + (drained ? "队列存量已处理完" : "队列未投完即退出") + "）");
    }

    private static ThreadFactory newWorkerFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, WORKER_THREAD_NAME);
            // 守护线程：即使 drain 失常也不阻塞 Tomcat / JVM 退出（对齐 mq-consumer）
            thread.setDaemon(true);
            return thread;
        };
    }
}
