package com.itheima.follow.service;

import com.itheima.config.AppConfig;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogContext;
import com.itheima.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱重建请求**去抖器**（feed3-T33-A，治 NEEDS 4.1 {@code N11} 的去抖面）：同一用户在**一个窗口**
 * （{@code feed.rebuild.debounceMillis}，默认 1000ms）内的多次关注 / 取关**只投一条重建消息**。
 *
 * <p><b>为什么需要（去抖与既有锁的分工）</b>：{@code FeedRebuildService} 的 {@code SET NX EX} 锁
 * 注释自证"只做并发去重优化、不聚合请求"；而 T31 之后投递是**单 worker 串行**（提交序 = 投递序）、
 * 消费是**单 channel / prefetch=1 串行** ⇒ N 条重建消息基本**不重叠**、逐条到达 ⇒ 那把锁**几乎
 * 永远拿得到**（去重失效）⇒ **N 次连续关注 ≈ N 次真实整窗重算**。本类把"事件即投递"改为
 * "事件入桶、桶静默满一个窗口后投一条"，是治 N11 的唯一动作，**不改变重建语义**
 * （重建仍是整窗重算 + "存在即已同步"）。
 *
 * <p><b>语义（尾沿 / trailing）</b>：每次 {@link #schedule} 重置该用户的窗口计时，**自最后一次事件起
 * 再等满窗口**才触发 ⇒ 合并窗口内 N 次事件为 1 条。窗口取值理由见 NEEDS 4.0 T33 段
 * （关注后用户行为是"进博主个人空间 / 继续刷别的"，异步重建下这点延迟无感）。
 *
 * <p><b>载体与依赖</b>：{@code ConcurrentHashMap<userId, Pending>}（{@code compute} 保证"取消旧计时 +
 * 排新计时"原子）+ 单个**守护** {@link ScheduledExecutorService}（JDK 自带，**零新依赖**）。
 * 本类**只负责"何时触发"**，不负责"怎么投"——到期回调由调用方给出（{@code InboxRebuildNotifier}
 * 传入的是"经 {@code MqDeliveryDispatcher} 异步投递"的任务体 ⇒ T31 的异步 / 队列满降级 / 关停 drain
 * 全部复用）。
 *
 * <p><b>关停</b>：{@link #destroy()} 先停收（{@code shuttingDown}）→ **flush**（把待触发项立即执行，
 * 使其进入 dispatcher 的 drain 通道）→ {@code shutdownNow}。flush **有界**（队列 = 待触发用户数），
 * 不挂住 Tomcat 关停；flush 后单条投递的成败仍由 dispatcher / {@code MqPublisher} 的既有降级口径持有。
 *
 * <p><b>日志口径（§3.1）</b>：常态零日志；**仅当实际发生合并（窗口内 &gt; 1 次）时**记一条 INFO 结论行
 * （"谁合并了多少次"）——既保持"成功路径不刷日志"（T22 口径），又给"连续 N 次只触发一次"提供留证口径；
 * 代际误删导致的多投、以及任务体异常穿透，都只影响幂等重放（重建整窗替换 ⇒ 无副作用）。
 *
 * <p><b>残余（已登记，见 NEEDS 4.0 T33 段）</b>：① 可见性上界 = 窗口 + 投递 + 消费；② 关停 flush 后
 * 仍受 dispatcher 残余②（连接先关即 fast-fail）约束；③ 内存去抖 = **单实例语义**（多实例部署需重评）；
 * ④ 持续高频事件流（同一用户间隔 &lt; 窗口且不停）下尾沿被无限推迟 —— 用户裁决"属不正常行为、
 * 责任归限流 · 风控专项"，故**刻意不加 maxDelay 上界**，且本类结构（map + 定时任务）**不随事件次数增长**。
 */
@Component
public class InboxRebuildDebouncer implements Disposable {

    private static final Logger LOGGER = LogUtil.getLogger(InboxRebuildDebouncer.class);

    /** 调度线程名（对齐 {@code mq-delivery} / {@code mq-consumer} 命名族）。 */
    private static final String THREAD_NAME = "mq-delivery-debounce";

    /** 待触发项（代次 + 窗口内合并次数 + 到期要执行的任务 + 排期句柄）。 */
    private record Pending(long token, int merged, Runnable task, ScheduledFuture<?> future) {
    }

    /** 代次序号：区分"同一用户的新旧待触发项"，供 {@link #fire} 做**条件删除**（避免误删并发排入的新条目）。 */
    private final AtomicLong generation = new AtomicLong();

    /** 待触发项（userId → Pending）；{@code compute} 之下"取消旧计时 + 排新计时 + 替换条目"整体原子。 */
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    /** 调度器；**窗口为 0（显式关闭去抖）时为 null**，此时不建线程池。 */
    private final ScheduledExecutorService scheduler;

    private final long windowMillis;

    /** 关停标志：令并发 {@link #schedule} 立即走"直接执行 / 丢弃"分支，不再排期。 */
    private volatile boolean shuttingDown;

    /** IoC 注入构造：窗口取自 {@code feed.rebuild.debounceMillis}（语义校验在 AppConfig）。 */
    @InjectConstructor
    public InboxRebuildDebouncer() {
        this(AppConfig.getFeedRebuildDebounceMillis());
    }

    /**
     * 受控构造（单测 / 特殊部署）：显式指定窗口毫秒。
     *
     * @throws IllegalArgumentException 窗口为负——负数无意义（0 有显式语义 = 关闭去抖），
     *                                  宁可构造即拒（对齐 {@code MqDeliveryDispatcher} / {@code MqConsumerContainer} 先例）
     */
    public InboxRebuildDebouncer(long windowMillis) {
        if (windowMillis < 0) {
            throw new IllegalArgumentException("feed.rebuild.debounceMillis 不得为负数: " + windowMillis);
        }
        this.windowMillis = windowMillis;
        this.scheduler = (windowMillis > 0L) ? newScheduler() : null;
    }

    /**
     * 登记一次"该用户需要重建收件箱"的请求（**非阻塞、绝不抛**，调用方是 Web 请求线程）。
     *
     * <p>同一 userId 在窗口内的多次调用只会在窗口末尾执行**最后一次**登记的 {@code task}；
     * 不同 userId 各自独立计时。窗口为 0（显式关闭去抖）或已关停 ⇒ **立即执行**（退回"事件即投递"
     * 的既有行为；调用方传入的 task 本身已是非阻塞的入队动作）。
     *
     * <p>{@code task} 经 {@link LogContext#wrap(Runnable)} 在**本方法调用线程**（Web 线程）捕获 reqId，
     * 到期在调度线程执行时恢复 —— 整条链（关注 / 取关 → 去抖 → dispatcher → 消费）的 {@code req=}
     * 不因去抖而断裂（LOG_CONVENTION §四"非 Web 线程"纪律 2）。
     *
     * @param userId 收件箱归属者（= 关注 / 取关的发起方）
     * @param task   到期要执行的动作（调用方给出的"投递重建消息"任务体，须自带根捕获）
     */
    public void schedule(long userId, Runnable task) {
        Objects.requireNonNull(task, "task");
        Runnable wrapped = LogContext.wrap(task);
        if (shuttingDown || windowMillis == 0L) {
            // 关停中 / 显式关闭去抖 ⇒ 立即执行（等价既有"事件即投递"；无合并语义）
            runTask(userId, wrapped);
            return;
        }
        try {
            pending.compute(userId, (key, current) -> {
                int merged = (current == null) ? 1 : current.merged() + 1;
                if (current != null) {
                    // 旧触发尚未开始时取消之；已开始执行的由"重建幂等 + 代次条件删除"兜底
                    current.future().cancel(false);
                }
                long token = generation.incrementAndGet();
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> fire(key, merged, wrapped, token), windowMillis, TimeUnit.MILLISECONDS);
                return new Pending(token, merged, wrapped, future);
            });
        } catch (RejectedExecutionException e) {
            // 关停竞态（destroy 已 shutdownNow）：降级丢弃，绝不向业务线程抛（关停期丢失 = 残余②口径）
            LOGGER.warning("重建请求去抖器已关停，本次重建请求被丢弃（降级，不影响关注/取关）: userId=" + userId);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 窗口到期触发：**先条件删除**（只有"我这一代"仍挂在 map 上才移除自己，避免误删并发排入的新条目；
     * 误删的后果也只是多投一次 —— 重建整窗替换 ⇒ 幂等）→ 记合并结论行（仅 &gt; 1 次）→ 执行任务。
     */
    private void fire(long userId, int merged, Runnable task, long token) {
        pending.computeIfPresent(userId, (key, current) -> (current.token() == token) ? null : current);
        if (merged > 1) {
            // 合并结论行**刻意在 LogContext.wrap 之外记录**（即不带 req=）：它聚合了窗口内来自
            // **多个请求**的登记，语义上不属任何单个 req；任务体自身的日志仍靠 wrapped 恢复 req=。
            LOGGER.log(Level.INFO, "收件箱重建请求已去抖合并, userId=" + userId
                    + ", merged=" + merged + ", windowMillis=" + windowMillis);
        }
        runTask(userId, task);
    }

    /** 执行任务体（**最后兜底**：任务体异常穿透只记 SEVERE + 栈，绝不外抛——本链不得影响业务线程）。 */
    private void runTask(long userId, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "收件箱重建去抖任务执行异常（已兜底，不影响关注/取关）, userId=" + userId, e);
        }
    }

    /**
     * 关停：停收新请求 → **flush 待触发项**（立即执行，使其进入 dispatcher 的 drain 通道；本步有界）
     * → {@code shutdownNow} 取消未到期的排期。每步不外抛，不挂住 Tomcat 关停。
     */
    @Override
    public void destroy() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        List<Map.Entry<Long, Runnable>> drained = new ArrayList<>();
        for (Long userId : new ArrayList<>(pending.keySet())) {
            Pending removed = pending.remove(userId);
            if (removed != null) {
                drained.add(Map.entry(userId, removed.task()));
            }
        }
        if (!drained.isEmpty()) {
            // 汇总一条（不按条刷）；单条投递成败仍由 dispatcher / MqPublisher 的既有降级口径持有
            LOGGER.log(Level.INFO, "重建请求去抖器关停，立即触发待合并的重建请求 " + drained.size()
                    + " 条（其投递受 dispatcher drain 上界约束）");
            for (Map.Entry<Long, Runnable> entry : drained) {
                runTask(entry.getKey(), entry.getValue());
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        LOGGER.log(Level.FINE, "重建请求去抖器已关闭（windowMillis=" + windowMillis + "）");
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            // 守护线程：即使关停流程异常也不阻塞 Tomcat / JVM 退出（对齐 mq-delivery / mq-consumer）
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
