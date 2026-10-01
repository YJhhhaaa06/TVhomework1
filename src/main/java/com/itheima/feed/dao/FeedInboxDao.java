package com.itheima.feed.dao;

import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 收件箱窗口落库 DAO（feed2-21 T21；feed2-22 T22 增重建侧三方法；feed2-23 T23 增读侧两方法）：写扩散把一条新内容写进
 * 作者每个粉丝的收件箱**DB 真相表** {@code feed_inbox}（{@code (user_id, content_id)} 唯一键
 * = 幂等去重 + 窗口读覆盖索引）；重建侧则**整窗替换**该用户的窗口行并维护
 * {@code feed_inbox_sync}（窗口同步状态，"存在即已同步"）。
 *
 * <p>范式：与本域既有 DAO 一致——方法接收外部 {@link Connection}（连接/事务边界由
 * {@code TransactionTemplate} 持有），{@code SQLException} 原样上抛由调用方定级记录。
 *
 * <p>写入形态：fanout 一轮粉丝窗口 = **一条多行 INSERT**（{@link #insertIgnoreBatch}）；
 * 重建 = {@link #deleteByUser} + {@link #insertBatch}（各一条语句，同一事务内）。
 * 不引入 {@code addBatch} / 逐行插入（多行 VALUES 一趟往返，与 {@code ContentDao}
 * 动态 IN 子句的拼装范式同构）。
 */
@Component
public class FeedInboxDao {

    /**
     * 批量幂等落库：向 {@code fanIds} 每个用户的收件箱追加 {@code contentId}。
     *
     * @param conn      外部事务连接
     * @param contentId 内容 id
     * @param fanIds    粉丝 id 列表（一轮窗口；空 / null 不发 SQL，返回 0）
     * @return 实际新增行数（重复行被 IGNORE，不计入）
     */
    public int insertIgnoreBatch(Connection conn, long contentId, List<Long> fanIds) throws SQLException {
        if (fanIds == null || fanIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("INSERT IGNORE INTO feed_inbox (user_id, content_id) VALUES ");
        for (int i = 0; i < fanIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long fanId : fanIds) {
                pstmt.setLong(idx++, fanId);
                pstmt.setLong(idx++, contentId);
            }
            return pstmt.executeUpdate();
        }
    }

    // ==================== 重建侧（feed2-22 T22：窗口替换 + 同步状态） ====================

    /**
     * 清空某用户收件箱窗口（重建第一步，"先清后建"的新载体 = DB 真相表）。
     *
     * <p>顺序红线（T22 重新论证）：本方法在重建事务内**先于**窗口重查执行，且其后事务内
     * **不再有任何删除动作**（窗口写入用 {@link #insertBatch} 的 `INSERT IGNORE`）——
     * 这样"清"与"建"之间到达的并发 fanout 增量只会**并集**进同一份快照（只多不丢）。
     *
     * @return 删除行数
     */
    public int deleteByUser(Connection conn, long userId) throws SQLException {
        String sql = "DELETE FROM feed_inbox WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            return pstmt.executeUpdate();
        }
    }

    /**
     * 窗口批量落库（重建产物）：向**同一用户**写入一批 {@code contentId}（一条多行 `INSERT IGNORE`）。
     *
     * <p>用 `INSERT IGNORE` 而非裸 `INSERT`：与并发 fanout 的 {@link #insertIgnoreBatch} 保持
     * 同一"幂等、无覆盖"语义（重复行靠 {@code uk_user_content} 静默忽略），使"重建整窗替换"
     * 与"fanout 增量"交错时结果为**并集**。
     *
     * @param contentIds 窗口内容 id（已归并去重降序裁剪；空 / null 不发 SQL，返回 0）
     * @return 实际新增行数
     */
    public int insertBatch(Connection conn, long userId, List<Long> contentIds) throws SQLException {
        if (contentIds == null || contentIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("INSERT IGNORE INTO feed_inbox (user_id, content_id) VALUES ");
        for (int i = 0; i < contentIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long contentId : contentIds) {
                pstmt.setLong(idx++, userId);
                pstmt.setLong(idx++, contentId);
            }
            return pstmt.executeUpdate();
        }
    }

    /**
     * 窗口同步状态落库（"存在即已同步"；本仓首个 upsert）。
     *
     * <p>必须与窗口替换**同一事务**调用：否则"同步态已写但窗口回滚"会让读侧（T23 读态闸门
     * 「未同步 → 回退纯拉」）误信一个陈旧窗口。**空窗口同样写**（"空但已同步" =
     * 该用户确实没有可看的内容，与 T19"空集也写完整态标记"同口径）。
     *
     * <p>{@code sync_time} 只作诊断（观测最近同步时刻），**不参与判定**、不做"过期重算"。
     *
     * <p>注：MySQL 的 `ON DUPLICATE KEY UPDATE` 在命中唯一键时仍会**消耗一次自增 `id`**
     * （`feed_inbox_sync.id` 会随重复同步增长）——仅影响观感，无正确性影响，不另行规避。
     */
    public void upsertSync(Connection conn, long userId) throws SQLException {
        String sql = "INSERT INTO feed_inbox_sync (user_id) VALUES (?)"
                + " ON DUPLICATE KEY UPDATE sync_time = CURRENT_TIMESTAMP";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        }
    }

    // ==================== 读侧（feed2-23 T23：同步闸门 + 窗口读取） ====================

    /**
     * 读态闸门：{@code userId} 的收件箱窗口**是否已同步**（feed2-23 T23）。
     *
     * <p>语义 = "存在即已同步"（T22 定义）：只有**重建**（关注 / 取关触发）会写入本表，而 fanout
     * 只向 {@code feed_inbox} 追增新行、**从不写同步状态** ⇒ 没有本行 = **该用户从没成功重建过**，
     * 其窗口要么为空、要么只有 fanout 散行（缺历史），**不可作为读源**（读侧回退既有纯拉）。
     *
     * <p>成本 = 一条 {@code uk_user} 唯一键点查（每请求一次；不做缓存——见 NEEDS 4.0 对齐补录）。
     * 注意 `feed_inbox_sync` 行**只 upsert、从不删除**，故"曾同步 ⇒ 现在仍同步"恒成立。
     *
     * @return true = 已同步（窗口可读）
     */
    public boolean existsSync(Connection conn, long userId) throws SQLException {
        String sql = "SELECT 1 FROM feed_inbox_sync WHERE user_id = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 读某用户收件箱窗口的**全部** contentId（feed2-23 T23 收件箱腿的 DB 装载器）。
     *
     * <p>**升序**返回（{@code ORDER BY content_id}）：与 {@code feed:inbox:{id}} 缓存 ZSet 的
     * score 序（score = contentId ⇒ ZRANGE 升序）**同向**，这样"缓存命中"与"回源回填"两条路径
     * 的成员序一致，读侧统一做一次内存反序即可得到内容倒序。
     *
     * <p>范围 = 该用户窗口的**全量行**（表侧由重建裁剪到 C、fanout 只追增，故量级 ≈ C），
     * 走 {@code uk_user_content(user_id, content_id)} 唯一键的索引区间扫描。
     *
     * <p>⚠️ **无 LIMIT**（feed3-T29 核算登记）：读侧最终只可见 M 条，但本查询会**装载该用户的全部行**，
     * 再在内存里截断 ⇒ 若表侧膨胀（fanout 只追增、且该用户久未重建），装载量与 Redis ZSet 体积随之
     * **无上界**。该残余 = `U-36`（`UNPLANNED_ISSUES.md`），修法（读侧加界）**不在 T29 范围**。
     */
    public List<Long> findInboxContentIds(Connection conn, long userId) throws SQLException {
        String sql = "SELECT content_id FROM feed_inbox WHERE user_id = ? ORDER BY content_id";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("content_id"));
                }
            }
        }
        return ids;
    }
}