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
            throw new DatabaseException("数据库操作失败", e);
        } catch (RuntimeException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (Exception e) {
            rollbackQuietly(conn);
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
