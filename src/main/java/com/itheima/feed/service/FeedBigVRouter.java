package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.follow.service.FollowCache;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 大V路由单点（feed2-21 T21）：判定"该作者是否大V"的**唯一判定点**——固定阈值 + 名单占位
 * （NEEDS 4.0 大V路由拍板；机制本体 / 阈值波动 / 掉粉补推 / 名单维护归三期）。
 *
 * <p><b>为什么单点</b>：同一判定被三处消费——fanout 写扩散（T21 接线：大V内容**不进**粉丝收件箱）、
 * 窗口重建（T22）、两路读的"大V发件箱"腿（T23）；三处若各自实现阈值/名单，口径必然漂移。
 *
 * <p><b>判定口径</b>（与配置同源，见 {@code app.properties} 的 feed.bigv.*）：
 * <ol>
 *   <li>名单命中（{@code feed.bigv.userIds}）⇒ 大V（显式指定，不经阈值）；</li>
 *   <li>否则粉丝数（{@link FollowCache#getFollowerCount}，缓存-降级复用关注域的既有读法）
 *       {@code >=} 阈值（{@code feed.bigv.threshold}，默认 10000）⇒ 大V。</li>
 * </ol>
 *
 * <p><b>失败面</b>：粉丝数读取异常 ⇒ 记 WARNING **结论行（不带栈**——源头 {@code FollowCache.loadCount}
 * 已持 SEVERE + 栈，§3.1 附加纪律 2"一次失败只允许一条带堆栈的记录"）并**按普通作者处理**
 * （fail-open：写路径失败方向取"宁可多写不少写"，由读路径按 contentId 去重兜底上行穿越残影）。
 * 方法**绝不抛**（配置键解析失败除外——属 fail-fast：非法 {@code feed.bigv.userIds} 会由
 * {@link AppConfig#getFeedBigVUserIds()} 抛 {@code IllegalArgumentException}，由调用链兜底记录）。
 *
 * <p><b>日志口径</b>：命中大V属**常态路由**，由调用方（写侧）记 FINE；本类只在降级时记 WARNING。
 */
@Component
public class FeedBigVRouter {

    private static final Logger LOGGER = LogUtil.getLogger(FeedBigVRouter.class);

    private final FollowCache followCache;

    @InjectConstructor
    public FeedBigVRouter(FollowCache followCache) {
        this.followCache = followCache;
    }

    /**
     * 判定作者是否大V（名单命中 OR 粉丝数 ≥ 阈值）。粉丝数读取失败 → WARNING（结论行）+ false。
     */
    public boolean isBigV(long authorId) {
        Set<Long> listed = AppConfig.getFeedBigVUserIds();
        if (listed.contains(authorId)) {
            return true;
        }
        int threshold = AppConfig.getFeedBigVThreshold();
        try {
            return followCache.getFollowerCount(authorId) >= threshold;
        } catch (RuntimeException e) {
            // 源头已持栈（FollowCache.loadCount / 缓存层）→ 此处只记结论行、不带栈（§3.1 附加纪律 2）
            LOGGER.log(Level.WARNING, "大V判定降级（粉丝数读取失败，按普通作者处理）, authorId=" + authorId);
            return false;
        }
    }
}