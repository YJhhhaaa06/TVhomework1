package com.itheima.util;

import com.itheima.exception.DatabaseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接池专项测试：由 surefire pool-test 执行单独 fork，环境变量
 * DB_POOL_INITSIZE=1 / DB_POOL_MAXSIZE=1 / DB_POOL_TIMEOUTMS=500，需要 MySQL 运行。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MyConnectionPoolTest {

    @AfterAll
    static void cleanup() {
        MyConnectionPool.closePool();
    }

    @Test
    @Order(1)
    void acquireTimesOutWhenPoolFull() throws SQLException {
        Connection held = MyConnectionPool.getConnection();
        long start = System.currentTimeMillis();
        try {
            SQLException ex = assertThrows(SQLException.class, MyConnectionPool::getConnection);
            assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
        } finally {
            MyConnectionPool.release(held);
        }
        assertTrue(System.currentTimeMillis() - start >= 400,
                "超时等待应接近 500ms");
    }

    @Test
    @Order(2)
    void acquireAgainAfterRelease() throws SQLException {
        Connection c1 = MyConnectionPool.getConnection();
        MyConnectionPool.release(c1);

        Connection c2 = MyConnectionPool.getConnection();
        assertNotNull(c2);
        MyConnectionPool.release(c2);
    }

    @Test
    @Order(3)
    void closedConnectionIsRemovedAndPoolCanRecreate() throws SQLException {
        Connection c1 = MyConnectionPool.getConnection();
        c1.close();
        MyConnectionPool.release(c1);

        Connection c2 = MyConnectionPool.getConnection();
        assertNotNull(c2);
        MyConnectionPool.release(c2);
    }

    /**
     * T11 定栈：事务基础设施异常（本执行 fork 为 pool-test：池容量 1 / 超时 500ms，故"占满池"
     * 即得 {@code SQLException("获取数据库连接超时")}）→ {@link TransactionTemplate} 记**唯一**
     * 带堆栈 SEVERE（被包成 DatabaseException 后出口只记 WARNING 无栈，故源头必须自记）。
     */
    @Test
    @Order(4)
    void transactionTemplateLogsSevereWhenConnectionExhausted() throws SQLException {
        Connection held = MyConnectionPool.getConnection();
        LogProbe probe = LogProbe.attachTo(LogUtil.getLogger(TransactionTemplate.class));
        try {
            assertThrows(DatabaseException.class,
                    () -> new TransactionTemplate().execute(c -> null));

            List<LogRecord> stacked = probe.records().stream()
                    .filter(r -> r.getThrown() != null).toList();
            assertEquals(1, stacked.size(), () -> "基础设施异常应恰一条带堆栈记录: " + probe.records());
            assertEquals(Level.SEVERE, stacked.getFirst().getLevel());
            assertEquals("事务失败，数据库操作失败", stacked.getFirst().getMessage());
            assertInstanceOf(SQLException.class, stacked.getFirst().getThrown(),
                    "堆栈根因应为连接池异常");
        } finally {
            probe.detach();
            MyConnectionPool.release(held);
        }
    }

    @Test
    @Order(5)
    void getAfterClosePoolThrows() {
        MyConnectionPool.closePool();

        assertThrows(IllegalStateException.class, MyConnectionPool::getConnection);
    }
}
