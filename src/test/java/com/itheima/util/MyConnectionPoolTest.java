package com.itheima.util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.SQLException;

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

    @Test
    @Order(4)
    void getAfterClosePoolThrows() {
        MyConnectionPool.closePool();

        assertThrows(IllegalStateException.class, MyConnectionPool::getConnection);
    }
}
