package com.itheima.util;

import com.itheima.exception.BusinessException;
import com.itheima.exception.DatabaseException;
import com.itheima.ioc.annotation.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 统一事务模板：取连接 → setAutoCommit(false) → 回调 → commit → finally 归还。
 * 业务异常原样重抛；SQLException 包装为 DatabaseException；其余 RuntimeException 原样重抛。
 */
@Component
public class TransactionTemplate {

    private static final Logger LOGGER = LogUtil.getLogger(TransactionTemplate.class);

    @FunctionalInterface
    public interface TransactionAction<T> {
        T execute(Connection conn) throws Exception;
    }

    public <T> T execute(TransactionAction<T> action) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            T result = action.execute(conn);
            conn.commit();
            return result;
        } catch (BusinessException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            // T11：本行是"事务基础设施异常"（获取连接 / 开启事务 / 提交）的**唯一带堆栈记录**。
            // 该异常被包成 DatabaseException（⊂ BusinessException）→ 出口 ExceptionFilter 按"可预期业务拒绝"
            // 只记 WARNING 且不带堆栈（见 LOG_CONVENTION §3.1 附加纪律 2 的"包装点即源头"）→ 故在此记栈。
            // 上游若自行记过（业务 lambda 内 catch）不会走到本分支（BusinessException 在前）；消息不含 SQL 文本/参数。
            LOGGER.log(Level.SEVERE, "事务失败，数据库操作失败", e);
            throw new DatabaseException("数据库操作失败", e);
        } catch (RuntimeException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (Exception e) {
            rollbackQuietly(conn);
            // T11 裁决：近乎不可达（动作体是 lambda，其 checked 异常只有 SQLException，已被上面的分支接走）
            // → 不补日志；若将来动作签名引入其它 checked 异常，须重新评估（评审 Y1 留痕）
            throw new DatabaseException("数据库操作失败", e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "事务回滚失败", e);
        }
    }
}
