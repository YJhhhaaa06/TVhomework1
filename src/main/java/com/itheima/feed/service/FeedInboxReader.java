package com.itheima.feed.service;

import com.itheima.cache.CacheKeys;
import com.itheima.cache.ZSetCache;
import com.itheima.config.AppConfig;
import com.itheima.exception.ServerException;
import com.itheima.feed.dao.FeedInboxDao;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 收件箱读腿（feed2-23 T23）：**读态闸门** + 「DB 真相 → 可降级读缓存」的窗口读取。
 *
 * <p><b>① 读态闸门（{@link #isSynced(long)}）</b>：{@code feed_inbox_sync} 存在即已同步
 * （T22 定义）。窗口里那批行只有**重建**（关注 / 取关触发）才会按窗口口径写全——fanout 只追增新行、
 * 从不写同步状态 ⇒ 没有同步行 = 该用户**从没成功重建过**（老用户 / 重建失败 / 消息丢失），
 * 其窗口要么空、要么只有零散增量，**不可作为读源**（调用方据此回退既有纯拉）。
 * 成本 = 一条 {@code uk_user} 唯一键点查（每请求一次，不做缓存——NEEDS 4.0 对齐补录口径）。
 *
 * <p><b>② 收件箱腿（{@link #readInbox(long)}）</b>：读 {@code feed:inbox:{userId}} 这个
 * **可降级读缓存**（miss → 回源 {@link FeedInboxDao#findInboxContentIds} 并回填；Redis 异常 →
 * DB 直查、不写回），返回 **contentId 降序**窗口。
 *
 * <p><b>为什么复用 {@link ZSetCache#getMembers} 而不用窗口读</b>：<br>
 * 1. {@code ZSetCache} 只有升序能力（{@code ZRANGE}），而 feed 需要"内容倒序"——本类取全量升序后
 * 做**一次内存反序**（窗口量级 ≈ C=200，成本可忽略），从而**完全不触碰** follow 域共享的
 * {@code ZSetCache}（零回归风险）；<br>
 * 2. 本键的写者只有本读路径（fanout / 重建**只 DEL 不写**）⇒ **{@code partial:} 前缀态永不产生**，
 * 于是"全量读即等于完整集合"成立（{@code getMembers} 的"部分态先补齐"分支退化为无害兜底）；<br>
 * 3. 顺带复用其三态（空标记）/单飞/滑动续期/降级与打点口径（HIT_DATA / HIT_EMPTY / MISS / DEGRADE
 * 自动落 **FEED** 域）。
 *
 * <p><b>方向一致性</b>：miss 与降级路径返回 loader 原序（SQL 为 {@code ORDER BY content_id ASC}），
 * 命中路径返回 {@code ZRANGE} 升序——两者**同向**，故反序后必为 contentId 降序。
 *
 * <p><b>失败面</b>：DB 失败一律**上抛**（{@code ServerException}），与切换前的纯拉语义一致
 * （读路径 DB 失败 = 500，不留"半条时间线"）；Redis 失败由 {@code ZSetCache} 内部降级吸收、不上抛。
 *
 * <p><b>日志口径（§3.1）</b>：DB 失败在本类（事务回调，包装点即源头）记 **SEVERE + 栈**；
 * 本类不额外记结论行（上层 {@code FeedReadService} 也不重复记——一次失败只允许一条带堆栈记录）。
 */
@Component
public class FeedInboxReader {

    private static final Logger LOGGER = LogUtil.getLogger(FeedInboxReader.class);

    private final FeedInboxDao feedInboxDao;
    private final ZSetCache zSetCache;
    private final TransactionTemplate transactionTemplate;

    @InjectConstructor
    public FeedInboxReader(FeedInboxDao feedInboxDao, ZSetCache zSetCache,
                           TransactionTemplate transactionTemplate) {
        this.feedInboxDao = feedInboxDao;
        this.zSetCache = zSetCache;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 读态闸门：该用户的收件箱窗口是否已同步（= 曾成功重建过）。
     *
     * <p>失败上抛 {@link ServerException}（该链唯一捕获点 = 事务回调，持 SEVERE + 栈）。
     */
    public boolean isSynced(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return feedInboxDao.existsSync(conn, userId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "读侧收件箱同步状态查询失败, userId=" + userId, e);
                throw new ServerException("收件箱读态查询失败");
            }
        });
    }

    /**
     * 读收件箱窗口（**contentId 降序**）：缓存三态读 + miss 回源回填 + Redis 降级走 DB。
     *
     * <p>空窗口返回空列表（含"已同步但确实没内容"——空标记 absorbing 该情形，不再打 DB）。
     */
    public List<Long> readInbox(long userId) {
        List<Long> ascending = zSetCache.getMembers(
                CacheKeys.feedInbox(userId),
                () -> loadInboxFromDb(userId),
                AppConfig.getFeedInboxTtlSeconds());
        List<Long> descending = new ArrayList<>(ascending);
        java.util.Collections.reverse(descending);   // ZRANGE 升序（=contentId 升序）→ 内容倒序
        return descending;
    }

    /**
     * DB 回源：读该用户窗口的全部 contentId（升序，与缓存 score 序同向）。
     *
     * <p>失败在事务回调内记 SEVERE + 栈后包 {@link ServerException} 上抛——由
     * {@code ZSetCache} 的单飞装载链原样透出（装载失败不写空标记、不 DEL，读侧下次自愈）。
     */
    private List<Long> loadInboxFromDb(long userId) {
        return transactionTemplate.execute(conn -> {
            try {
                return feedInboxDao.findInboxContentIds(conn, userId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "收件箱窗口 DB 装载失败, userId=" + userId, e);
                throw new ServerException("收件箱窗口装载失败");
            }
        });
    }
}
