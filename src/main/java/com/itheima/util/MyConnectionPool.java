package com.itheima.util;

import com.itheima.config.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MyConnectionPool {
    private static final Logger LOGGER = LogUtil.getLogger(MyConnectionPool.class);

    private static final String URL = AppConfig.getDbUrl();
    private static final String USER = AppConfig.getDbUsername();
    private static final String PASSWORD = AppConfig.getDbPassword();

    //手动加载驱动
    static {
        try {
            Class.forName(AppConfig.getDbDriver());
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "MySQL 驱动加载失败", e);
        }
    }

    // 池子
    private static final int INIT_SIZE = AppConfig.getDbInitSize();
    private static final int MAX_SIZE = AppConfig.getDbMaxSize();
    private static final long TIMEOUT_MS = AppConfig.getDbTimeoutMs();
    private static final LinkedList<Connection> pool = new LinkedList<>();
    private static final Set<Connection> allConnections = new HashSet<>();
    //连接池是否已关闭
    private static volatile boolean isClosed = false;

    // 静态代码块初始化
    static {
        if (MAX_SIZE < INIT_SIZE) {
            throw new IllegalStateException(
                    "db.pool.maxSize(" + MAX_SIZE + ") 不能小于 db.pool.initSize(" + INIT_SIZE + ")");
        }
        try {
            for (int i = 0; i < INIT_SIZE; i++) {
                createConnection();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "初始化数据库连接池失败", e);
        }
    }
    private static void createConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
        pool.add(conn);
        allConnections.add(conn);
    }

    // 获取连接
    public static synchronized Connection getConnection() throws SQLException {
        if (isClosed) {
            throw new IllegalStateException("连接池已关闭");
        }
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (pool.isEmpty()) {
            if (allConnections.size() < MAX_SIZE) {
                createConnection();
                break;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new SQLException("获取数据库连接超时");
            }
            try {
                MyConnectionPool.class.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("获取数据库连接被中断");
            }
        }
        return pool.removeFirst();
    }

    // 归还连接
    public static synchronized void release(Connection conn) {
        if (conn == null) return;

        try {
            // 检查连接是否还活着
            if (conn.isClosed()) {
                allConnections.remove(conn);
                return;
            }

            if (!conn.isValid(1)) {
                conn.close();
                allConnections.remove(conn);
                return;
            }

            //  重置状态
            conn.setAutoCommit(true);

            //  归还连接池
            pool.addLast(conn);
            MyConnectionPool.class.notifyAll();

        } catch (SQLException e) {
            // 只要出异常 → 直接销毁连接
            try {
                conn.close();
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            } finally {
                allConnections.remove(conn);
            }
        }
    }

    public static synchronized void closePool() {
        isClosed = true;
        for (Connection conn : allConnections) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "关闭数据库连接失败", e);
            }
        }
        pool.clear();
        allConnections.clear();
        MyConnectionPool.class.notifyAll();
    }



}
