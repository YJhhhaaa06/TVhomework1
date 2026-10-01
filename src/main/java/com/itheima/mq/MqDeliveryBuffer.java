package com.itheima.mq;

import com.itheima.config.AppConfig;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 投递补偿缓冲（feed3-T33-B，治 NEEDS 4.1 {@code N11} 的补偿面）：MQ 不可用时**未投出**的消息暂存到
 * 有界内存队列，**恢复后重放**——三类消息（写扩散 {@code feed.push.content} / 降级补推
 * {@code feed.push.backfill} / 收件箱重建 {@code feed.rebuild.inbox}）**统一经过本类**。
 *
 * <p><b>为什么能安全重放</b>：三类消息的消费侧**本就幂等**（重建 = 整窗替换 + {@code SET NX EX} 去重锁；
 * fanout / 补推 = {@code INSERT IGNORE}），故重放是"至少一次"语义、零副作用——这是本方案成立的基石，
 * **不需要为它新增任何东西**。
 *
 * <p><b>入缓冲判据 = {@link MqConnectionProvider#ensureConnected()} 返回 false</b>（唯一能"确定没投出去"
 * 的口径）。注意**必须用 {@code ensureConnected()} 而非 {@code isAvailable()}**：前者同时是
 * {@code MqConnectionManager} 惰性重连的**唯一触发点**，若只读本地状态就会让"首次连不上 → 惰性重连"
 * 这条既有机制失去触发者（连接再也回不来）。**确认超时 / nack 不入缓冲**——那是"已投出但状态不明"，
 * 重放可能双投；保持 {@link MqPublisher} 既有降级口径不动（不扩范围）。
 *
 * <p><b>重放触发 = 双通道</b>（NEEDS 4.0 T33 段拍板）：
 * <ul>
 *   <li><b>主通道 = 定时惰性探测</b>（{@link #PROBE_INTERVAL_MILLIS}，包内常量 30s）：缓冲非空且连接
 *       可用即重放。**为什么必须有它**：{@code ConnectionFactory.automaticRecoveryEnabled = true} 下
 *       **运行期断线由客户端自动恢复、不经过 {@code MqConnectionManager.establish()}、不通知任何
 *       listener** ⇒ 只挂 {@code onConnected} 覆盖不到最常见的故障模式（运行期 MQ 挂 → 起），方案会形同
 *       虚设。</li>
 *   <li><b>加速通道 = {@link MqConnectionListener#onConnected()}</b>：令"首次连不上 → 惰性重连成功"
 *       这条路径立即重放，不必等下一个探测周期。</li>
 * </ul>
 * 与 {@code MqConnectionManager} 的惰性重连同构：**不做常驻重连线程**，探测任务在缓冲为空时零动作。
 *
 * <p><b>失败与容量口径</b>：缓冲**有界**（{@code feed.compensate.bufferCapacity}，默认 10000）——
 * 满则丢弃新消息，**首次记一条 WARNING 后节流**（不按条刷），队列重新腾出后自动恢复告警能力。
 * 重放中若连接再次不可用 ⇒ **停止本轮并保留剩余**（下次探测继续）；单条未确认 ⇒ 放回队尾并结束本轮
 * （避免在坏状态下空转）。
 *
 * <p><b>契约</b>：本类**绝不抛异常**（与 {@link MqPublisher} / {@link MqDeliveryDispatcher} 同族），
 * 供投递线程调用；不改变任何既有降级语义。
 *
 * <p><b>残余（已登记，见 NEEDS 4.0 T33 段）</b>：① 内存缓冲 ⇒ **应用重启 / 崩溃即丢**（跨重启不可补，
 * 与"关停期消息丢失"残余②同族）；② 缓冲满 ⇒ 丢弃 + 节流 WARNING；③ 只覆盖"provider 不可用"这一类
 * 确定未投递；④ 重放 = 至少一次 ⇒ **依赖消费侧幂等**（已具备，登记为依赖项）；⑤ 恢复后可补偿的时延
 * 上界 = **探测周期 + 投递 + 消费**。
 */
@Component
public class MqDeliveryBuffer implements Initializable, Disposable {

    private static final Logger LOGGER = LogUtil.getLogger(MqDeliveryBuffer.class);

    /** 探测线程名（对齐 {@code mq-delivery} / {@code mq-consumer} 命名族）。 */
    private static final String THREAD_NAME = "mq-delivery-buffer";

    /**
     * 恢复探测周期（毫秒，**包内常量**）：与 {@code MqConnectionManager.RECONNECT_COOLDOWN_MILLIS}
     * 同量级（30s），走"非必要不入配置"口径——探测本身零 I/O（缓冲为空时直接返回），成本可忽略；
     * 它同时决定"恢复后可补偿"的时延上界。
     */
    static final long PROBE_INTERVAL_MILLIS = 30_000L;

    private final MqConnectionProvider provider;
    private final MqPublisher publisher;

    /** 有界待重放队列（容量 = {@code feed.compensate.bufferCapacity}）。 */
    private final ArrayBlockingQueue<MqMessage> pending;

    private final ScheduledExecutorService scheduler;

    /** 重放互斥：探测线程与连接回调（MQ 客户端线程）可能并发触发。 */
    private final AtomicBoolean flushing = new AtomicBoolean();

    /** 溢出告警节流：首次满记一条 WARNING，之后静默；队列腾出后重置（不按条刷日志）。 */
    private final AtomicBoolean overflowWarned = new AtomicBoolean();

    /** 关停标志：之后不再缓冲、不再重放（关停期丢失 = 残余②口径）。 */
    private volatile boolean shuttingDown;

    /** IoC 注入构造：容量取自 {@code feed.compensate.bufferCapacity}（语义校验在 AppConfig）。 */
    @InjectConstructor
    public MqDeliveryBuffer(MqConnectionManager connectionManager, MqPublisher publisher) {
        this(connectionManager, publisher, AppConfig.getFeedCompensateBufferCapacity());
    }

    /**
     * 受控构造（单测 / 特殊部署）：显式指定缓冲容量。
     *
     * @throws IllegalArgumentException 容量非正——非正会让缓冲永远无法入队（补偿功能整体失效），
     *                                  宁可构造即拒（对齐 {@code MqDeliveryDispatcher} / T29 批量尺寸口径）
     */
    MqDeliveryBuffer(MqConnectionProvider provider, MqPublisher publisher, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("feed.compensate.bufferCapacity 必须为正数: " + capacity);
        }
        this.provider = provider;
        this.publisher = publisher;
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.scheduler = newScheduler();
    }

    /**
     * 投递一条消息（**绝不抛**）：连接不可用 ⇒ 暂存待补偿；可用 ⇒ 交 {@link MqPublisher}（未确认仍走
     * 其既有降级口径、**不入缓冲**）。
     *
     * @return true = 已确认；false = 已暂存 / 降级（调用方无需处理异常，与 {@code MqPublisher} 同语义）
     */
    public boolean publish(MqMessage message) {
        if (message == null || message.body() == null) {
            return false;
        }
        if (shuttingDown) {
            return false;   // 关停期不再缓冲（缓冲也无从重放）
        }
        if (!provider.ensureConnected()) {
            // 唯一"确定没投出去"的口径；同时保住"惰性重连触发点"（见类注释）
            buffer(message, "连接不可用");
            return false;
        }
        return publisher.publish(message);
    }

    /** 当前待重放条数（诊断 / 单测断言用）。 */
    int pendingCount() {
        return pending.size();
    }

    // ==================== 生命周期 ====================

    /**
     * 注册连接回调（加速通道）并启动定时探测（主通道）。**绝不抛**（IoC 红线：{@code init()} 抛异常会
     * 阻断 Tomcat 启动）；任一步失败只记 WARNING，降级为"仅剩一条通道"或"不补偿"。
     */
    @Override
    public void init() {
        try {
            provider.addConnectionListener(this::onConnected);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MQ 投递补偿：连接回调注册失败（降级，仅剩定时探测通道）", e);
        }
        try {
            scheduler.scheduleWithFixedDelay(this::probe,
                    PROBE_INTERVAL_MILLIS, PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MQ 投递补偿：定时探测启动失败（降级，仅剩连接回调通道）", e);
        }
    }

    /** 关停：停收 → 丢弃剩余（汇总一条 INFO）→ 关探测线程；每步不外抛，不挂住 Tomcat 关停。 */
    @Override
    public void destroy() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        int remaining = pending.size();
        pending.clear();
        if (remaining > 0) {
            LOGGER.log(Level.INFO, "MQ 投递补偿缓冲关停：丢弃未重放的 " + remaining
                    + " 条（关停期丢失 = NEEDS 4.3 残余②口径，由收件箱重建兜底）");
        }
        scheduler.shutdownNow();
        LOGGER.log(Level.FINE, "MQ 投递补偿缓冲已关闭");
    }

    // ==================== 内部实现 ====================

    /** 新连接已建立（加速通道）：立即尝试重放（本回调在 MQ 客户端线程执行，不外抛）。 */
    private void onConnected() {
        flush("onConnected");
    }

    /**
     * 定时探测（主通道）：缓冲非空且连接可用才重放；**任何异常都吞掉**——任务体抛出会让
     * {@code scheduleWithFixedDelay} 永久停摆。包级可见：单测可直接驱动该路径（不依赖真实计时）。
     */
    void probe() {
        try {
            if (!pending.isEmpty() && provider.isAvailable()) {
                flush("定时探测");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MQ 投递补偿探测异常（忽略，等下次探测）", e);
        }
    }

    /**
     * 重放（互斥）：逐条投出；连接再次不可用 ⇒ **停止并保留剩余**（下次探测继续）；单条未确认 ⇒
     * 放回队尾并结束本轮。投出 &gt; 0 时记一条 INFO 结论行（常态零输出）。
     */
    private void flush(String trigger) {
        if (shuttingDown || !flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            if (pending.isEmpty()) {
                return;
            }
            int delivered = 0;
            while (!pending.isEmpty()) {
                if (shuttingDown || !provider.ensureConnected()) {
                    break;   // 仍不可用：保留剩余，等下次探测 / 回调
                }
                MqMessage message = pending.poll();
                if (message == null) {
                    break;
                }
                if (publisher.publish(message)) {
                    delivered++;
                } else {
                    // 未确认（nack / 超时）：放回队尾并结束本轮，避免坏状态下空转
                    pending.offer(message);
                    break;
                }
            }
            if (delivered > 0) {
                overflowWarned.set(false);   // 队列已腾出 ⇒ 恢复溢出告警能力
                LOGGER.log(Level.INFO, "MQ 恢复，补偿重放完成: 本次投出 " + delivered
                        + " 条, trigger=" + trigger + ", pending=" + pending.size());
            }
        } catch (Exception e) {
            // 兜底：本类契约"绝不抛"（调用方可能是 MQ 客户端回调线程）
            LOGGER.log(Level.WARNING, "MQ 投递补偿重放异常（已兜底，剩余待下次探测）", e);
        } finally {
            flushing.set(false);
        }
    }

    /** 暂存一条（满则丢弃 + **节流** WARNING）。 */
    private void buffer(MqMessage message, String reason) {
        if (pending.offer(message)) {
            LOGGER.fine("投递暂存待补偿（" + reason + "）, routingKey=" + message.routingKey()
                    + ", pending=" + pending.size());
            return;
        }
        if (overflowWarned.compareAndSet(false, true)) {
            // 首次溢出记一条（之后静默，队列腾出后自动恢复告警）——不按条刷日志
            LOGGER.log(Level.WARNING, "MQ 投递补偿缓冲已满，新投递被丢弃（降级，不影响业务；"
                    + "本条之后同类丢弃不再逐条告警）, routingKey=" + message.routingKey()
                    + ", capacity=" + pending.size());
        }
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
