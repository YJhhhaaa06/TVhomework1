package com.itheima.follow.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.follow.model.dto.InboxRebuildMessage;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.mq.MqDeliveryBuffer;
import com.itheima.mq.MqDeliveryDispatcher;
import com.itheima.mq.MqMessage;
import com.itheima.mq.MqPublisher;
import com.itheima.mq.MqTopology;
import com.itheima.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱重建投递封装（feed1-19 T19）：关注 / 取关 → 投递 rebuild 消息，令**自己的**收件箱失效并重建。
 *
 * <p><b>语义</b>（NEEDS 4.0 机制骨架）：关注（新博主的既有内容要进来）与取关（该博主的内容要出去）
 * 都会让本人收件箱的"关注者内容快照"失效 ⇒ 投一条 {@link InboxRebuildMessage}，
 * 由 {@code feed.rebuild.queue} 的消费者执行**窗口重建**（T22 起 = 单事务整窗替换
 * {@code feed_inbox} + 写 {@code feed_inbox_sync}，随后失效读缓存；见
 * {@code FeedRebuildService.rebuildInbox}）。
 *
 * <p><b>红线</b>：本类**任何情况下都不抛异常**——投递失败只降级（该次重建缺失，
 * 由下次关注 / 取关或读侧"未同步 ⇒ 回退纯拉"兜底），**关注 / 取关接口的响应与语义一概不变**；
 * **不触碰 {@code /feed} 读路径**（读侧自 T23 起为两路读窗口，与本投递解耦）。
 *
 * <p><b>异步投递（feed3-T31，治 NEEDS 4.1 N8）</b>：「序列化 + publish + waitForConfirms」经
 * {@link MqDeliveryDispatcher}（专用单 worker + 有界队列）在后台线程完成，关注 / 取关接口 RT 不再等
 * confirm（最长 5s）；队列满 / 已关停 ⇒ 任务被丢弃并记 WARNING（降级，残余②口径）。
 * 本方法的日志点 / 级别 / 消息内容**一概不变**，只是执行线程从 Web 线程换为 {@code mq-delivery}：
 * 根捕获（SEVERE + 栈）随任务体走（LOG_CONVENTION §四"非 Web 线程"纪律 1）；{@code req=} 串联由
 * dispatcher 的 {@code LogContext.wrap} 在提交线程捕获完成（纪律 2）。投递序 = 提交序（单 worker），
 * 同一用户的重建消息不会因异步化而乱序。
 *
 * <p><b>去抖（feed3-T33-A，治 NEEDS 4.1 {@code N11} 的去抖面）</b>：本方法**不再直接入 dispatcher**，
 * 而是先登记到 {@link InboxRebuildDebouncer}（per-user 尾沿合并，窗口 = {@code feed.rebuild.debounceMillis}
 * 默认 1000ms）；窗口到期后仍经 {@code dispatcher.submit} 走**同一条**异步投递链（T31 的 RT 解耦 /
 * 队列满降级 / 关停 drain 一概复用）。**重建语义零改动**（仍整窗重算 + "存在即已同步"），改变的只是
 * "同一用户的连点 / 批量关注投几条"；可见性上界由"投递 + 消费"变为"**窗口 + 投递 + 消费**"
 * （残余见去抖器类注释与 NEEDS 4.0 T33 段）。
 *
 * <p><b>投递点口径</b>：由调用方（{@code FollowService}）在**事务提交之后**调用，位置与既有
 * "缓存双写"并列（先例：同方法的 {@code followCache.cacheFollow} / {@code cacheUnfollow}；
 * 本项目无事务同步 / afterCommit 机制）；晚于里程碑 INFO，与 {@code LOG_CONVENTION} §3.6 一致。
 *
 * <p><b>依赖（IoC 约束）</b>：{@code MqDeliveryBuffer} / {@code JacksonCodec} / {@code MqDeliveryDispatcher} /
 * {@code InboxRebuildDebouncer} 均按**具体类**注入——IoC 按具体类解析依赖（{@code beans.get(paramType)}），
 * 写接口会取不到 Bean 而硬 fail-fast。
 *
 * <p><b>日志口径（§3.1）</b>：序列化失败 = 该链唯一捕获点 → WARNING **持栈**；投递未确认沿用
 * {@link MqPublisher} 口径——不刷 WARNING（broker 不可用是连接级故障，MQ 侧已记一次），
 * 只留默认不输出的 FINE 开发诊断信息。
 *
 * <p><b>为何在 follow 域</b>：见 {@link InboxRebuildMessage} 类注释（避免新增跨域包环）。
 */
@Component
public class InboxRebuildNotifier {

    private static final Logger LOGGER = LogUtil.getLogger(InboxRebuildNotifier.class);

    /** 投递入口（feed3-T33-B 起：经 {@link MqDeliveryBuffer}，MQ 不可用时暂存、恢复后重放）。 */
    private final MqDeliveryBuffer deliveryBuffer;
    private final JacksonCodec codec;
    private final MqDeliveryDispatcher dispatcher;
    /** 重建请求去抖（feed3-T33-A）：把"同一用户的连点 / 批量关注"合并为一条重建投递。 */
    private final InboxRebuildDebouncer debouncer;

    @InjectConstructor
    public InboxRebuildNotifier(MqDeliveryBuffer deliveryBuffer, JacksonCodec codec,
                                MqDeliveryDispatcher dispatcher, InboxRebuildDebouncer debouncer) {
        this.deliveryBuffer = deliveryBuffer;
        this.codec = codec;
        this.dispatcher = dispatcher;
        this.debouncer = debouncer;
    }

    /**
     * 投递"收件箱需要重建"事件（只带 userId）——**异步**：入队即返回（入队失败也已降级，见下）。
     *
     * <p>幂等性说明：MQ 侧不保证只投一次（本期无重试，但客户端 automatic recovery 可能重发）；
     * 接收侧重建是**整窗重算同一窗口**（单事务清空 `feed_inbox` + 按当前关注关系重写窗口 +
     * 写 `feed_inbox_sync`），且有 SET NX 去重锁 ⇒ **重复投递无副作用**。与并发 fanout 交错时，
     * 结果为**并集**——相对本次有界快照"只多不丢"（fanout 只追增、窗口内此后不再有删除动作；
     * 见 {@code FeedRebuildService} 的顺序红线论证与 NEEDS 4.0 T22 残余）。
     *
     * @param userId 收件箱归属者（= 关注 / 取关的**发起方**，不是被关注的博主）
     */
    public void publishInboxRebuild(long userId) {
        // feed3-T33-A：先入**去抖桶**（窗口内同一 userId 只保留最后一次，见 InboxRebuildDebouncer）——
        // 去抖器在**调用线程**捕获 reqId，到期在调度线程恢复后再提交给 dispatcher，req= 链不断裂。
        debouncer.schedule(userId, () -> dispatcher.submit(() -> {
            try {
                doPublish(userId);
            } catch (RuntimeException e) {
                // 契约"绝不抛"的最后兜底（随任务体在投递线程执行，LOG_CONVENTION §四"非 Web 线程"纪律 1）：
                // 任何意外运行时异常都不得影响业务（需人介入 → SEVERE + 栈）。
                // 正常路径不经过这里（序列化失败已在内层按降级 WARNING 处理）。
                LOGGER.log(Level.SEVERE, "收件箱重建投递异常（已兜底，不影响关注/取关）, userId=" + userId, e);
            }
        }, "rebuild userId=" + userId));
    }

    /** 投递主体：序列化失败 → WARNING（该链唯一捕获点，持栈）；未确认 → 只留默认不输出的 FINE 诊断。 */
    private void doPublish(long userId) {
        byte[] body;
        try {
            String json = codec.toJson(new InboxRebuildMessage(userId));
            if (json == null) {
                LOGGER.log(Level.WARNING, "收件箱重建投递跳过（载荷序列化为空，不影响关注/取关）, userId=" + userId);
                return;
            }
            body = json.getBytes(StandardCharsets.UTF_8);
        } catch (CacheException e) {
            // 该链唯一捕获点（§3.1 附加纪律 2 例外②"吸收点即唯一捕获点"必须持栈）
            LOGGER.log(Level.WARNING, "收件箱重建投递跳过（载荷序列化失败，不影响关注/取关）, userId=" + userId, e);
            return;
        }
        if (!deliveryBuffer.publish(MqMessage.rebuild(MqTopology.RK_REBUILD_INBOX, body))) {
            // 降级口径：不可用时 MqDeliveryBuffer 只暂存并留 FINE 诊断（不刷 WARNING）；
            // 真正发布失败的 WARNING + 栈由 MqPublisher 持（那里是该链唯一捕获点），此处不重复记
            LOGGER.fine("收件箱重建投递未确认（降级或已暂存待补偿，不影响关注/取关）, userId=" + userId);
        }
    }
}
