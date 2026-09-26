package com.itheima.feed.dao;

import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 收件箱窗口落库 DAO（feed2-21 T21）：写扩散把一条新内容写进作者每个粉丝的收件箱**DB 真相表**
 * {@code feed_inbox}（{@code (user_id, content_id)} 唯一键 = 幂等去重 + 窗口读覆盖索引）。
 *
 * <p>范式：与本域既有 DAO 一致——方法接收外部 {@link Connection}（连接/事务边界由
 * {@code TransactionTemplate} 持有），{@code SQLException} 原样上抛由调用方定级记录。
 *
 * <p>写入形态：一轮粉丝窗口 = **一条多行 INSERT**（{@code INSERT IGNORE}；重复行靠
 * {@code uk_user_content} 静默忽略 ⇒ 幂等，重复投递无副作用）。不引入 {@code addBatch} /
 * 逐行插入（多行 VALUES 一趟往返，与 {@code ContentDao} 动态 IN 子句的拼装范式同构）。
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
}