package com.itheima.follow.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.follow.model.dto.InboxRebuildMessage;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
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
 * 由 {@code feed.rebuild.queue} 的消费者执行 {@code DEL → DB 重查 → ZADD 合并} 后标"完整态"。
 *
 * <p><b>红线（影子期）</b>：本类**任何情况下都不抛异常**——投递失败只降级（该次重建缺失，
 * 由下次关注/取关或二期读触发兜底），**关注 / 取关接口的响应与语义一概不变**；也不触碰
 * {@code /feed} 读路径（一期仍纯拉模式）。
 *
 * <p><b>投递点口径</b>：由调用方（{@code FollowService}）在**事务提交之后**调用，位置与既有
 * "缓存双写"并列（先例：同方法的 {@code followCache.cacheFollow} / {@code cacheUnfollow}；
 * 本项目无事务同步 / afterCommit 机制）；晚于里程碑 INFO，与 {@code LOG_CONVENTION} §3.6 一致。
 *
 * <p><b>依赖（IoC 约束）</b>：{@code MqPublisher} / {@code JacksonCodec} 均按**具体类**注入——
 * IoC 按具体类解析依赖（{@code beans.get(paramType)}），写接口会取不到 Bean 而硬 fail-fast。
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

    private final MqPublisher publisher;
    private final JacksonCodec codec;

    @InjectConstructor
    public InboxRebuildNotifier(MqPublisher publisher, JacksonCodec codec) {
        this.publisher = publisher;
        this.codec = codec;
    }

    /**
     * 投递"收件箱需要重建"事件（只带 userId）。
     *
     * <p>幂等性说明：MQ 侧不保证只投一次（本期无重试，但客户端 automatic recovery 可能重发）；
     * 接收侧重建设有 SET NX 去重锁且产物是同一份 DB 快照，故重复投递无副作用。
     *
     * @param userId 收件箱归属者（= 关注 / 取关的**发起方**，不是被关注的博主）
     */
    public void publishInboxRebuild(long userId) {
        try {
            doPublish(userId);
        } catch (RuntimeException e) {
            // 契约"绝不抛"的最后兜底：任何意外运行时异常都不得穿透到关注 / 取关接口（需人介入 → SEVERE + 栈）。
            // 正常路径不经过这里（序列化失败已在内层按降级 WARNING 处理）。
            LOGGER.log(Level.SEVERE, "收件箱重建投递异常（已兜底，不影响关注/取关）, userId=" + userId, e);
        }
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
        if (!publisher.publish(MqMessage.rebuild(MqTopology.RK_REBUILD_INBOX, body))) {
            // MqPublisher 的降级口径：不可用时既不访问 broker 也不记日志（避免每请求刷日志）；
            // 真正发布失败的 WARNING + 栈由 MqPublisher 持（那里是该链唯一捕获点），此处不重复记
            LOGGER.fine("收件箱重建投递未确认（降级，不影响关注/取关）, userId=" + userId);
        }
    }
}
