package com.itheima.user.service;

import com.itheima.config.FeedBigVConfig;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.dao.UserDao;
import com.itheima.util.LogUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自动大V状态维护（**写侧判定单点**，feed3-T28-A）：把"谁是大V"从**无状态读时求值**升级为**带滞回**的
 * 判定——升级线 {@code 粉丝数 >= 阈值}、降级线 {@code 粉丝数 < 系数 × 阈值}（系数键
 * {@code feed.bigv.downgradeRatio}，默认 0.8）；两线之间（带内）**状态不翻转**。
 *
 * <p><b>为什么在 user 域</b>（红线）：本类被 {@code follow.service.FollowService} 在**关注 / 取关事务内**
 * 调用。若把它放进 feed 域，会新增 {@code follow → feed} 依赖（{@code feed → follow} 已存在）而成**包环**；
 * 状态表本身也是"用户属性"（`users.follower_count` 的派生状态），落 user 域与 {@code UserDao} 同层最自然。
 *
 * <p><b>状态表语义 = 「存在即自动大V」</b>（{@code auto_bigv}，PK {@code user_id}）：升 = {@code INSERT IGNORE}、
 * 降 = {@code DELETE}，**两者的 affected rows 即 edge 信号**（"本次是否真发生状态迁移"）——
 * 刻意**不用**"比较 before/after 计数"（并发下两个取关可能都判"跨越" ⇒ 重复触发），也不用
 * "level 触发 + 由消费者决定"（那要求消费者自持一份补推状态）。返回值交调用方用于"提交后"记录，
 * 也是 feed3-T28-B 降级补推的输入。
 *
 * <p><b>判定口径（本类唯一实现）</b>：
 * <ol>
 *   <li>{@code 粉丝数 >= 阈值} ⇒ {@code INSERT IGNORE}（幂等：已在表中 affected = 0 ⇒ 无 edge）；</li>
 *   <li>{@code 粉丝数 < 系数 × 阈值} ⇒ {@code DELETE}（不在表中 affected = 0 ⇒ 无 edge）；</li>
 *   <li>带内（{@code [系数×阈值, 阈值)}）⇒ **不动**（这就是滞回：已升者不因掉到带内而翻转）。</li>
 * </ol>
 * 粉丝数**只取 DB 真值**（{@code users.follower_count}，与 feed3-T26 起的读侧判定同源）；阈值 / 系数经
 * {@link FeedBigVConfig#current()} 取**同一快照**（同批热更，不会出现"新阈值 + 旧系数"）。
 * **刻意不看名单**：名单是读侧的显式覆盖（名单命中即大V），不影响"该用户粉丝数是否达线"这一事实；
 * 由此产生的后果（名单变动不触发 follow 事件 ⇒ 存量状态行不会被自动清理）属已登记残余
 * （"配置驱动的批量降级覆盖不到"）。
 *
 * <p><b>为什么在同一事务内读计数</b>：本类由 {@code FollowService} 在 {@code updateFollowerCount(±1)}
 * **之后、同一 conn** 调用 —— ① **fail-atomic**：判定与计数同生共死（状态不会与计数脱节）；
 * ② **天然串行**：该 UPDATE 已持有该用户 users 行的排它锁，两笔并发关注 / 取关在该行上串行
 * ⇒ 后面读到的计数与状态表操作都基于**已串行化**的最新值 ⇒ "并发两次取关只有先到者 affected == 1"
 * 由行锁保证（无需额外 CAS / 版本号）。**不用** {@code FollowCache} 的计数缓存做预筛：该缓存双写在
 * **提交后**（把 INCRBY 提前进事务会改动缓存失败语义、放大改动面），而此处只多一次主键点查。
 *
 * <p><b>失败面</b>：{@code SQLException} ⇒ 本类记 {@code SEVERE} + **堆栈**（"包装点即源头"，§3.1 附加纪律 2）
 * 并抛 {@link ServerException} ⇒ **整个关注 / 取关事务回滚**（fail-atomic，与同事务内的
 * {@code updateFollowerCount} 失败同口径）。**配置非法**（阈值 / 系数越界）⇒ 照旧向上抛
 * {@link IllegalArgumentException}（fail-fast，与读侧判定同口径，不做静默降级）。
 * 成功的状态迁移**不在此处记日志**：调用方在**事务提交后**记（"成功里程碑只在提交后写"）。
 */
@Component
public class AutoBigVStateService {

    private static final Logger LOGGER = LogUtil.getLogger(AutoBigVStateService.class);

    private final UserDao userDao;
    /**
     * 阈值 / 系数**取值单点**（feed3-T27-A 立、feed3-T28-A 起含系数）：本类不直读 {@code AppConfig}。
     */
    private final FeedBigVConfig bigVConfig;

    @InjectConstructor
    public AutoBigVStateService(UserDao userDao, FeedBigVConfig bigVConfig) {
        this.userDao = userDao;
        this.bigVConfig = bigVConfig;
    }

    /** 一次评估的状态迁移结果（{@link #NONE} = 状态未变）。 */
    public enum Transition {
        /** 状态未变（带内 / 已在表中 / 本就不在表中）。 */
        NONE,
        /** 本次真升级（首次入表）。 */
        UPGRADED,
        /** 本次真降级（本次删到行）——feed3-T28-B 降级补推的触发信号。 */
        DOWNGRADED
    }

    /**
     * 在**调用方事务内**（同一 {@code conn}）评估并维护 {@code userId} 的自动大V状态。
     *
     * @param conn   调用方事务连接（**必须是**已执行 {@code updateFollowerCount} 的那个连接）
     * @param userId **被关注者**（粉丝数变化者），非操作者
     * @return 状态迁移结果（供调用方在提交后记录 / 供 T28-B 触发补推）
     */
    public Transition evaluate(Connection conn, long userId) {
        // 取值单点：阈值与系数来自**同一快照**（同批热更）；配置非法 fail-fast（不吞）
        FeedBigVConfig.Snapshot active = bigVConfig.current();
        int threshold = active.threshold();
        double downgradeLine = active.downgradeRatio() * threshold;
        try {
            int followerCount = userDao.getFollowerCountById(conn, userId);
            if (followerCount >= threshold) {
                return userDao.insertAutoBigV(conn, userId) ? Transition.UPGRADED : Transition.NONE;
            }
            if (followerCount < downgradeLine) {
                return userDao.deleteAutoBigV(conn, userId) ? Transition.DOWNGRADED : Transition.NONE;
            }
            return Transition.NONE;      // 带内：滞回生效，不翻转
        } catch (SQLException e) {
            // 该链唯一捕获点（包装点即源头，§3.1 附加纪律 2）：SEVERE + 栈；
            // 抛 ServerException ⇒ 关注 / 取关事务整体回滚（fail-atomic，不给"计数已变但状态没跟上"的中间态）
            LOGGER.log(Level.SEVERE, "自动大V状态维护失败, userId=" + userId, e);
            throw new ServerException("自动大V状态维护失败");
        }
    }
}
