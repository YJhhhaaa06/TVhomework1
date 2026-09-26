package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.exception.CacheException;
import com.itheima.feed.model.dto.FeedPushMessage;
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
 * 写扩散投递封装（feed1-18 T18）：内容发布 → 投递 push 消息，交由消费者写各粉丝收件箱。
 *
 * <p><b>红线（影子期）</b>：本类**任何情况下都不抛异常**——投递失败只降级（消息缺失至下次重建，
 * 属 NEEDS 4.0 已登记的残余窗口 a），**不影响发布接口的响应与语义**，也不改 `/feed` 读路径。
 *
 * <p><b>投递点口径</b>：由调用方（{@code ContentService}）在**事务提交之后**调用（"提交后副作用"
 * 的既有范式，先例：同方法的 {@code contentCache.addContent}；本项目无事务同步 / afterCommit 机制）。
 * 晚于里程碑 INFO → 与 {@code LOG_CONVENTION} §3.6"事务提交后、缓存同步前"的位置口径一致。
 *
 * <p><b>依赖（IoC 约束）</b>：{@code MqPublisher} / {@code JacksonCodec} 均按**具体类**注入——
 * IoC 按具体类解析依赖（{@code beans.get(paramType)}），写接口会取不到 Bean 而硬 fail-fast。
 *
 * <p><b>日志口径（§3.1）</b>：序列化失败 = 该链唯一捕获点 → WARNING **持栈**；投递未确认
 * 沿用 {@link MqPublisher} 口径——不刷 WARNING（broker 不可用是连接级故障，MQ 侧已记一次），
 * 只留默认不输出的 FINE 开发诊断信息。
 */
@Component
public class FeedPushNotifier {

    private static final Logger LOGGER = LogUtil.getLogger(FeedPushNotifier.class);

    private final MqPublisher publisher;
    private final JacksonCodec codec;

    @InjectConstructor
    public FeedPushNotifier(MqPublisher publisher, JacksonCodec codec) {
        this.publisher = publisher;
        this.codec = codec;
    }

    /**
     * 投递"内容已发布"事件（内容 id + 作者 id）。
     *
     * <p>幂等性说明：MQ 侧不保证只投一次（本期无重试，但客户端 automatic recovery 可能重发）；
     * 接收侧收件箱写入用 ZADD，天然幂等，故重复投递无副作用。
     *
     * @param contentId 新内容 id（收件箱成员 / ZSet score）
     * @param authorId  作者 id（接收侧据此取粉丝列表）
     */
    public void publishContentPublished(long contentId, long authorId) {
        try {
            doPublish(contentId, authorId);
        } catch (RuntimeException e) {
            // 契约"绝不抛"的最后兜底：任何意外运行时异常都不得穿透到发布接口（需人介入 → SEVERE + 栈）。
            // 正常路径不经过这里（序列化失败已在内层按降级 WARNING 处理）。
            LOGGER.log(Level.SEVERE, "写扩散投递异常（已兜底，不影响发布）, contentId=" + contentId, e);
        }
    }

    /** 投递主体：序列化失败 → WARNING（该链唯一捕获点，持栈）；未确认 → 只留默认不输出的 FINE 诊断。 */
    private void doPublish(long contentId, long authorId) {
        byte[] body;
        try {
            String json = codec.toJson(new FeedPushMessage(contentId, authorId));
            if (json == null) {
                LOGGER.log(Level.WARNING, "写扩散投递跳过（载荷序列化为空，不影响发布）, contentId=" + contentId);
                return;
            }
            body = json.getBytes(StandardCharsets.UTF_8);
        } catch (CacheException e) {
            // 该链唯一捕获点（§3.1 附加纪律 2 例外②"吸收点即唯一捕获点"必须持栈）
            LOGGER.log(Level.WARNING, "写扩散投递跳过（载荷序列化失败，不影响发布）, contentId=" + contentId, e);
            return;
        }
        if (!publisher.publish(MqMessage.push(MqTopology.RK_PUSH_CONTENT, body))) {
            // MqPublisher 的降级口径：不可用时既不访问 broker 也不记日志（避免每请求刷日志）；
            // 真正发布失败的 WARNING + 栈由 MqPublisher 持（那里是该链唯一捕获点），此处不重复记
            LOGGER.fine("写扩散投递未确认（降级，不影响发布）, contentId=" + contentId);
        }
    }
}
