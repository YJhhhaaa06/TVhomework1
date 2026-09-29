package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.config.FeedBigVConfig;
import com.itheima.exception.ServerException;
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
 * 大V路由单点（feed2-21 T21 立；feed3-T26 口径统一 + 写侧批量判定）：判定"该作者是否大V"的
 * **唯一判定点**——固定阈值 + 名单。
 *
 * <p><b>为什么单点</b>：同一判定被三处消费——fanout 写扩散（大V内容**不进**粉丝收件箱）、
 * 窗口重建（排除大V作者）、两路读的"大V发件箱"腿（读谁的发件箱）；三处若各自实现阈值 / 名单，
 * 口径必然漂移。
 *
 * <p><b>判定口径（feed3-T26 统一；feed3-T27-A 起取值收敛）</b>：阈值 / 名单**不直读 {@link AppConfig}**，
 * 一律经 {@link FeedBigVConfig#current()} 取**不可变快照**——该单点负责"值从哪来、何时刷新"（支持外部
 * 文件热更，见其类注释），本类不再承担。键仍为 {@code app.properties} 的 {@code feed.bigv.*}：
 * <ol>
 *   <li>名单命中（{@code feed.bigv.userIds}）⇒ 大V（显式指定，不经阈值、也不进 SQL 的 IN 列表）；</li>
 *   <li>否则粉丝数（**DB 真值** {@code users.follower_count}，按 {@code feed.bigv.queryBatch} **分块多条 SQL**批量判定）
 *       {@code >=} 阈值（{@code feed.bigv.threshold}，默认 10000）⇒ 大V。</li>
 * </ol>
 *
 * <p><b>口径统一为此改了两次</b>（NEEDS 4.0 T26 拍板，方向 = 全链统一到 DB 真值）：
 * <ul>
 *   <li>T26 之前，单作者 {@code isBigV} 走 {@code FollowCache.getFollowerCount}（**计数缓存**）、
 *       批量走 {@code users.follower_count}（**DB 真值**）⇒ 两侧来源不同，极端时序下判定可分歧；</li>
 *   <li>T26 起**只有一个实现**：{@link #isBigV(long)} 直接**委派** {@link #isBigVBatch(List)}
 *       （`List.of(id)`），三处消费者同源同口径；代价 = fanout 每次发布由"Redis 计数命中"变为
 *       一次 DB 单行查询（发生在 MQ 消费线程，不在 Web 线程）——已在 NEEDS 4.0 登记接受。
 *       本类因此**不再依赖 {@code FollowCache}**（该计数缓存仍服务 `/profile`，不可删）。</li>
 * </ul>
 *
 * <p><b>批量为何分块</b>：{@link #isBigVBatch(List)} 的 IN 列表长度 ∝ 关注数（无上限：重建 /
 * 读侧都带上整个关注集）⇒ 按 {@link AppConfig#getFeedBigVQueryBatch()}（默认 200）切分为多条 SQL。
 * 切分发生在**同一个事务回调内**：一次连接借还 + **同一 read view**（`TransactionTemplate` 只做
 * `setAutoCommit(false)`、不设隔离级别 ⇒ MySQL 默认 REPEATABLE READ）⇒ **跨块无时序偏差**。
 *
 * <p><b>失败面（fail-open）</b>：任一块的 SQL 失败 ⇒ 结论行 WARNING（**不带栈**——源头 {@code SQLException}
 * 分支已持 SEVERE + 栈，§3.1 附加纪律 2"一次失败只允许一条带堆栈的记录"）+ **整批只返回名单命中项**
 * （当作"其余都是普通作者"）。**不做"成功块部分合并"**——半批口径不如整批退回到名单项干净。
 * 写路径失败方向取"宁可多写不少写"，由读路径按 contentId 去重兜底上行穿越残影；
 * 读路径少显示一批大V内容、**不 500**（下次请求自愈）。
 *
 * <p><b>配置非法一律 fail-fast（向上抛）</b>：{@code feed.bigv.userIds} 解析失败、
 * {@code feed.bigv.threshold} / {@code feed.bigv.queryBatch} 解析失败或非正数 ⇒
 * {@link IllegalArgumentException}，且**均发生在 try 之外**（否则会被 fail-open 捕获吞掉、静默按普通作者判定）。
 * **外部热更文件的坏值不在此列**（feed3-T27-A）——文件不存在 / 不可读 / 阈值非数字 / 名单含非法 id 由
 * {@link FeedBigVConfig} **整批拒绝并沿用上次快照**（首次则回落本档静态值），不向上抛。
 * 除配置非法外，方法**绝不抛**。
 *
 * <p><b>日志口径</b>：命中大V属**常态路由**，由调用方（写侧）记 FINE；本类只在降级时记 WARNING
 * （两个入口共用同一条结论行措辞）。
 */
@Component
public class FeedBigVRouter {

    private static final Logger LOGGER = LogUtil.getLogger(FeedBigVRouter.class);

    private final UserDao userDao;
    private final TransactionTemplate transactionTemplate;
    /**
     * 名单 / 阈值**取值单点**（feed3-T27-A）：本类不再直读 {@link AppConfig} 取判定输入，
     * 一律经它取不可变快照 ⇒ 将来换载体（文件 → 表 + 版本轮询）本类与三处调用方零改动。
     */
    private final FeedBigVConfig bigVConfig;

    @InjectConstructor
    public FeedBigVRouter(UserDao userDao, TransactionTemplate transactionTemplate,
                          FeedBigVConfig bigVConfig) {
        this.userDao = userDao;
        this.transactionTemplate = transactionTemplate;
        this.bigVConfig = bigVConfig;
    }

    /**
     * 判定作者是否大V（名单命中 OR 粉丝数 ≥ 阈值）。
     *
     * <p>feed3-T26 起**委派**批量入口（口径唯一实现，见类注释）；粉丝数 = DB 真值。
     * 名单命中 / 判定失败（fail-open 按普通作者）等语义与 {@link #isBigVBatch(List)} 完全一致。
     */
    public boolean isBigV(long authorId) {
        return isBigVBatch(List.of(authorId)).contains(authorId);
    }

    /**
     * **批量**判定：返回 {@code authorIds} 中属于大V的子集（fanout / 重建 / 读三处共用；feed3-T26 起
     * 单作者入口亦委派至此 ⇒ 本方法是判定规则的**唯一实现**）。
     *
     * <p><b>形态</b>：名单项先在 Java 侧并入（**命中名单者不再进 IN 列表**，少查一批）；其余按
     * {@link AppConfig#getFeedBigVQueryBatch()} 分块，**同一事务**内逐块调用
     * {@code UserDao#findUserIdsByMinFollowerCount}（{@code follower_count >= 阈值 AND id IN (…)}）。
     * 结果并入 {@link LinkedHashSet}（插入序 = 入参序，便于调用方按需复用顺序，但**调用方保序不依赖本序**）。
     *
     * <p><b>失败面</b>：见类注释（整批 fail-open 只保留名单项；配置非法向上抛）。
     *
     * @param authorIds 待判定作者（空 / null → 空集，且**不读任何配置、不发 SQL**）
     * @return 其中的大V子集（**不含**非大V）
     */
    public Set<Long> isBigVBatch(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptySet();
        }
        // 取值单点（feed3-T27-A）：阈值 + 名单来自**同一快照**（同批热更，不会出现"新名单 + 旧阈值"）
        FeedBigVConfig.Snapshot active = bigVConfig.current();
        Set<Long> listed = active.userIds();
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
        int threshold = active.threshold();
        // 注意：批量尺寸是**执行参数**、不是判定输入，故不入热更快照（走 AppConfig 静态值，改动仍需重启）
        int batch = AppConfig.getFeedBigVQueryBatch();
        if (batch <= 0) {
            // 非正批量会让下面的分块循环无法前进 ⇒ fail-fast。**必须在 try 之外**：进了 try 会被
            // fail-open 捕获吞掉、静默按普通作者判定（与"配置非法照旧抛"的既有口径相悖）。
            throw new IllegalArgumentException("feed.bigv.queryBatch 必须为正数: " + batch);
        }
        try {
            bigVs.addAll(transactionTemplate.execute(conn -> {
                try {
                    List<Long> found = new ArrayList<>(toQuery.size());
                    for (int from = 0; from < toQuery.size(); from += batch) {
                        int to = Math.min(from + batch, toQuery.size());
                        found.addAll(userDao.findUserIdsByMinFollowerCount(conn, toQuery.subList(from, to), threshold));
                    }
                    return found;
                } catch (SQLException e) {
                    // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈
                    LOGGER.log(Level.SEVERE, "大V批量判定查询失败, authorCount=" + toQuery.size(), e);
                    throw new ServerException("大V批量判定失败");
                }
            }));
        } catch (RuntimeException e) {
            // 源头已持栈 → 此处只记结论行、不带栈；fail-open 整批只保留名单项（见方法注释）
            LOGGER.log(Level.WARNING, "大V判定降级（粉丝数查询失败，按普通作者处理，名单项保留）, authorCount="
                    + toQuery.size());
        }
        return bigVs;
    }
}
