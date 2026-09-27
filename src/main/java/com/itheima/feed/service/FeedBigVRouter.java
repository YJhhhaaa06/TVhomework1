package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.exception.ServerException;
import com.itheima.follow.service.FollowCache;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.dao.UserDao;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p><b>单点判定的两个入口（规模不同、规则同源）</b>：
 * {@link #isBigV(long)}（单作者，写侧用：fanout / 重建，走 {@code FollowCache} 粉丝数缓存）与
 * {@link #isBigVBatch(List)}（feed2-23 T23 增，**读侧专用**：一次 SQL 判定"关注集里哪些是大V"，
 * 取代"逐作者串行"）。两者共用同一份**阈值 + 名单**规则（本类内唯一表达），差别只在数据来源与规模。
 *
 * <p><b>失败面</b>：粉丝数读取异常 ⇒ 记 WARNING **结论行（不带栈**——源头 {@code FollowCache.loadCount}
 * 已持 SEVERE + 栈，§3.1 附加纪律 2"一次失败只允许一条带堆栈的记录"）并**按普通作者处理**
 * （fail-open：写路径失败方向取"宁可多写不少写"，由读路径按 contentId 去重兜底上行穿越残影）；
 * 批量入口的 SQL 失败同样 fail-open（**只保留名单命中项**，读侧少显示一批大V内容、下次请求自愈）。
 * 方法**绝不抛**（配置键解析失败除外——属 fail-fast：非法 {@code feed.bigv.userIds} 会由
 * {@link AppConfig#getFeedBigVUserIds()} 抛 {@code IllegalArgumentException}，由调用链兜底记录）。
 *
 * <p><b>日志口径</b>：命中大V属**常态路由**，由调用方（写侧）记 FINE；本类只在降级时记 WARNING。
 */
@Component
public class FeedBigVRouter {

    private static final Logger LOGGER = LogUtil.getLogger(FeedBigVRouter.class);

    private final FollowCache followCache;
    private final UserDao userDao;
    private final TransactionTemplate transactionTemplate;

    @InjectConstructor
    public FeedBigVRouter(FollowCache followCache, UserDao userDao,
                          TransactionTemplate transactionTemplate) {
        this.followCache = followCache;
        this.userDao = userDao;
        this.transactionTemplate = transactionTemplate;
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

    /**
     * **批量**判定：返回 {@code authorIds} 中属于大V的子集（feed2-23 T23，**读侧专用**）。
     *
     * <p><b>为什么需要批量入口</b>：读侧要回答"我关注的人里哪些是大V"（据此决定读谁的 outbox），
     * 而**关注数无上限**（{@code FollowService.follow} 无数量限制）⇒ 逐作者调 {@link #isBigV}
     * 是一条"∝ 关注数"的串行链（还带 N 次 Redis 往返）。本方法把阈值判定压成**一条 SQL**
     * （{@code users.follower_count >= 阈值}，见 {@code UserDao#findUserIdsByMinFollowerCount}），
     * 名单项在 Java 侧并入、且**命中名单的作者不再进 IN 列表**（少查一批）。
     *
     * <p><b>规则同源</b>：阈值与名单仍只在本类表达（与 {@link #isBigV} 同一份配置键），
     * 唯一差别 = 粉丝数来源（本方法走 `users.follower_count` **DB 真值**；{@link #isBigV} 走
     * {@code FollowCache} 计数缓存）。二者在运行期等价（缓存即由该列装载），极端时序下可能有偏差，
     * 由读侧按 contentId 去重兜底（同 T22 已登记的"来源不同"口径）。
     *
     * <p><b>失败面（fail-open）</b>：SQL 失败 ⇒ 结论行 WARNING（**不带栈**——源头在事务回调内已持
     * SEVERE + 栈）+ **只返回名单命中项**（当作"其余都是普通作者"）⇒ 本次读少一批大V内容、
     * **不 500**（与"大V腿是补充腿"的定位一致；下次请求自愈）。注意：此时若回退纯拉，
     * 会因为同一 DB 不可用而失败，故 fail-open 是更稳的方向。
     *
     * <p>配置非法（{@code feed.bigv.userIds} 解析失败）仍**向上抛**（fail-fast，同 {@link #isBigV}）。
     *
     * @param authorIds 待判定作者（空 / null → 空集）
     * @return 其中的大V子集（插入序 = 入参序；**不含**非大V）
     */
    public Set<Long> isBigVBatch(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> listed = AppConfig.getFeedBigVUserIds();
        Set<Long> bigVs = new LinkedHashSet<>();
        List<Long> toQuery = new ArrayList<>(authorIds.size());
        for (Long authorId : authorIds) {
            if (listed.contains(authorId)) {
                bigVs.add(authorId);      // 名单命中：不经阈值、也不必进 IN 列表
            } else {
                toQuery.add(authorId);
            }
        }
        if (toQuery.isEmpty()) {
            return bigVs;
        }
        int threshold = AppConfig.getFeedBigVThreshold();
        try {
            bigVs.addAll(transactionTemplate.execute(conn -> {
                try {
                    return userDao.findUserIdsByMinFollowerCount(conn, toQuery, threshold);
                } catch (SQLException e) {
                    // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈
                    LOGGER.log(Level.SEVERE, "大V批量判定查询失败, authorCount=" + toQuery.size(), e);
                    throw new ServerException("大V批量判定失败");
                }
            }));
        } catch (RuntimeException e) {
            // 源头已持栈 → 此处只记结论行、不带栈；fail-open 保留名单项（见方法注释）
            LOGGER.log(Level.WARNING, "大V批量判定降级（按普通作者处理，名单项保留）, authorCount="
                    + toQuery.size());
        }
        return bigVs;
    }
}