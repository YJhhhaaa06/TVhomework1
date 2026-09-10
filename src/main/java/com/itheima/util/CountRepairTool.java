package com.itheima.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 计数修复工具（运维用，可批量重算各类计数字段）。
 *
 * 自 T4 起，相同能力由 tools/check_integrity.py 的统一入口提供：
 *   - 计数漂移只读检查：python tools/check_integrity.py
 *   - 计数修复：        python tools/check_integrity.py --fix
 * 运维优先使用统一工具（dry-run 默认 + 显式 --fix + 单事务 + 报告落 .docs/temp/）；
 * 本类保留，供直连调试或与统一工具结果交叉验证。SQL 语义一致，勿在此处新增漂移场景。
 */
public class CountRepairTool {

    public static void main(String[] args) {
        System.out.println("===== 计数字段修复开始 =====");

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            int totalRows = 0;
            totalRows += repair("content.like_count",
                    "UPDATE content c SET c.like_count = (SELECT COUNT(*) FROM content_like WHERE content_id = c.id)",
                    conn);
            totalRows += repair("comment.like_count",
                    "UPDATE comment cm SET cm.like_count = (SELECT COUNT(*) FROM comment_like WHERE comment_id = cm.comment_id)",
                    conn);
            totalRows += repair("content.comment_count",
                    "UPDATE content c SET c.comment_count = (SELECT COUNT(*) FROM comment WHERE content_id = c.id AND is_deleted = 0)",
                    conn);
            totalRows += repair("users.follow_count",
                    "UPDATE users u SET u.follow_count = (SELECT COUNT(*) FROM follow WHERE user_id = u.id)",
                    conn);
            totalRows += repair("users.follower_count",
                    "UPDATE users u SET u.follower_count = (SELECT COUNT(*) FROM follow WHERE followed_user_id = u.id)",
                    conn);

            conn.commit();
            System.out.println("===== 修复完成，共更新 " + totalRows + " 行 =====");

        } catch (Exception e) {
            System.err.println("修复失败: " + e.getMessage());
            e.printStackTrace();
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("已回滚");
                } catch (SQLException ex) {
                    System.err.println("回滚失败: " + ex.getMessage());
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private static int repair(String label, String sql, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            System.out.println("[" + label + "] 更新了 " + rows + " 行");
            return rows;
        }
    }
}