package com.itheima.follow.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.follow.model.dto.AuthorBackfillMessage;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.mq.MqDeliveryDispatcher;
import com.itheima.mq.MqMessage;
import com.itheima.mq.MqPublisher;
import com.itheima.mq.MqTopology;
import com.itheima.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 降级补推投递封装（feed3-T28-B）：作者由大V降为普通（{@code AutoBigVStateService} 的
 * {@code DOWNGRADED} edge）⇒ 投递 {@link AuthorBackfillMessage}，令消费者把该作者最近 K 条内容
 * 补写进现任粉丝收件箱（见 {@code FeedInboxWriter.backfillAuthor}）。
 *
 * <p><b>为什么需要补推</b>（NEEDS 4.0 T28 拍板④）：大V期间其内容**不进粉丝收件箱**（由"大V发件箱"
 * 读时拉）；降级后若只等"下次关注 / 取关触发的重建"，存量内容会出现可见性断档——补推把这段
 * 断档消除，且**不依赖**该粉丝的下一次关注 / 取关。
 *
 * <p><b>红线</b>：本类**任何情况下都不抛异常**——投递失败只降级（该次补推缺失，由下次重建 /
 * 再次降级 edge 兜底；跨 MQ 故障的缺口补偿统一归 feed3-T33，**不在此另写补偿**），
 * **关注 / 取关接口的响应与语义一概不变**；**不触碰 {@code /feed} 读路径**。
 *
 * <p><b>异步投递（沿 feed3-T31 管线）</b>：「序列化 + publish + waitForConfirms」经
 * {@link MqDeliveryDispatcher}（专用单 worker + 有界队列）在后台线程完成，取关接口 RT 不再等
 * confirm；队列满 / 已关停 ⇒ 任务被丢弃并记 WARNING（降级）。
 *
 * <p><b>投递点口径</b>：由调用方（{@code FollowService}）在**事务提交之后**、且**仅当**
 * 迁移 = {@code DOWNGRADED} 时调用（与 {@code logBigVTransition} 同源信号，先例 =
 * {@code InboxRebuildNotifier} 的"提交后副作用"）。
 *
 * <p><b>依赖（IoC 约束）</b>：{@code MqPublisher} / {@code JacksonCodec} / {@code MqDeliveryDispatcher}
 * 均按**具体类**注入——IoC 按具体类解析依赖（{@code beans.get(paramType)}），写接口会取不到 Bean 而硬 fail-fast。
 *
 * <p><b>日志口径（§3.1）</b>：序列化失败 = 该链唯一捕获点 → WARNING **持栈**；投递未确认沿用
 * {@link MqPublisher} 口径——不刷 WARNING（broker 不可用是连接级故障，MQ 侧已记一次），
 * 只留默认不输出的 FINE 开发诊断信息。
 *
 * <p><b>为何在 follow 域</b>：见 {@link AuthorBackfillMessage} 类注释（避免新增跨域包环）。
 */
@Component
public class AuthorBackfillNotifier {

    private static final Logger LOGGER = LogUtil.getLogger(AuthorBackfillNotifier.class);

    private final MqPublisher publisher;
    private final JacksonCodec codec;
    private final MqDeliveryDispatcher dispatcher;

    @InjectConstructor
    public AuthorBackfillNotifier(MqPublisher publisher, JacksonCodec codec,
                                  MqDeliveryDispatcher dispatcher) {
        this.publisher = publisher;
        this.codec = codec;
        this.dispatcher = dispatcher;
    }

    /**
     * 投递"作者降级需补推"事件（只带 authorId）——**异步**：入队即返回（入队失败也已降级，见下）。
     *
     * <p>幂等性说明：MQ 侧不保证只投一次（本期无重试，但客户端 automatic recovery 可能重发）；
     * 接收侧补推是 `INSERT IGNORE` 追增（同 contentId 重复行被唯一键静默忽略）⇒ **重复投递无副作用**。
     *
     * @param authorId 降级的作者（= 关注 / 取关的**被关注者**，不是操作者）
     */
    public void publishAuthorBackfill(long authorId) {
        dispatcher.submit(() -> {
            try {
                doPublish(authorId);
            } catch (RuntimeException e) {
                // 契约"绝不抛"的最后兜底（随任务体在投递线程执行，LOG_CONVENTION §四"非 Web 线程"纪律 1）：
                // 任何意外运行时异常都不得影响业务（需人介入 → SEVERE + 栈）。
                // 正常路径不经过这里（序列化失败已在内层按降级 WARNING 处理）。
                LOGGER.log(Level.SEVERE, "降级补推投递异常（已兜底，不影响关注/取关）, authorId=" + authorId, e);
            }
        }, "backfill authorId=" + authorId);
    }

    /** 投递主体：序列化失败 → WARNING（该链唯一捕获点，持栈）；未确认 → 只留默认不输出的 FINE 诊断。 */
    private void doPublish(long authorId) {
        byte[] body;
        try {
            String json = codec.toJson(new AuthorBackfillMessage(authorId));
            if (json == null) {
                LOGGER.log(Level.WARNING, "降级补推投递跳过（载荷序列化为空，不影响关注/取关）, authorId=" + authorId);
                return;
            }
            body = json.getBytes(StandardCharsets.UTF_8);
        } catch (CacheException e) {
            // 该链唯一捕获点（§3.1 附加纪律 2 例外②"吸收点即唯一捕获点"必须持栈）
            LOGGER.log(Level.WARNING, "降级补推投递跳过（载荷序列化失败，不影响关注/取关）, authorId=" + authorId, e);
            return;
        }
        if (!publisher.publish(MqMessage.push(MqTopology.RK_PUSH_BACKFILL, body))) {
            // MqPublisher 的降级口径：不可用时既不访问 broker 也不记日志（避免每请求刷日志）；
            // 真正发布失败的 WARNING + 栈由 MqPublisher 持（那里是该链唯一捕获点），此处不重复记
            LOGGER.fine("降级补推投递未确认（降级，不影响关注/取关）, authorId=" + authorId);
        }
    }
}