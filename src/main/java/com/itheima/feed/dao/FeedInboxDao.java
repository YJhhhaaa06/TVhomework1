package com.itheima.feed.dao;

import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 收件箱窗口落库 DAO（feed2-21 T21；feed2-22 T22 增重建侧三方法）：写扩散把一条新内容写进
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
     * <p>必须与窗口替换**同一事务**调用：否则"同步态已写但窗口回滚"会让读侧（T23 读态闸门 /
     * T24「未同步 → 回退纯拉」）误信一个陈旧窗口。**空窗口同样写**（"空但已同步" =
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
}