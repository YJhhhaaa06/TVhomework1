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
    private static final LinkedList<Connection> pool = new LinkedList<>();
    private static final Set<Connection> allConnections = new HashSet<>();
    //连接池是否已关闭
    private static volatile boolean isClosed = false;

    // 静态代码块初始化
    static {
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
        if(isClosed){
            throw new IllegalStateException("连接池已关闭");
        }
        if (pool.isEmpty()) {
            // 没有就新建
            createConnection();
        }
        return pool.removeFirst();
    }

    // 归还连接
    public static synchronized void release(Connection conn) {
        //synchronized 线程锁    保证同一时刻只有一个线程用这个方法
        if (conn == null) return;

        try {
            // 检查连接是否还活着（建议加）
            if (conn.isClosed()) {
                return; // 已经关了，直接丢弃
            }

            if (!conn.isValid(1)) {
                conn.close();//等一秒钟还没确认conn是有效的，关闭后丢弃
                return;
            }

            //  重置状态
            conn.setAutoCommit(true);

            //  归还连接池
            pool.addLast(conn);

        } catch (SQLException e) {
            // 只要出异常 → 直接销毁连接
            try {
                conn.close();
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            }
        }
    }

    public static synchronized void closePool() {
        isClosed=true;
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
    }



}
