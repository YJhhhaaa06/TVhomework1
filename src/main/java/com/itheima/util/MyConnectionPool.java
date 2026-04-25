package com.itheima.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedList;

public class MyConnectionPool {
//    private static final String URL = "jdbc:mysql:///TVDatabase?useSSL=false";
private static final String URL = "jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASSWORD = "MySQL";

    //手动加载驱动
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    // 池子
    private static final int INIT_SIZE = 5;
    private static final LinkedList<Connection> pool = new LinkedList<>();

    // 静态代码块初始化
    static {
        try {
            for (int i = 0; i < INIT_SIZE; i++) {
                Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
                pool.add(conn);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 获取连接
    public static synchronized Connection getConnection() throws SQLException {
        if (pool.isEmpty()) {
            // 没有就新建（简单策略）
            return DriverManager.getConnection(URL, USER, PASSWORD);
        }
        return pool.removeFirst();
    }

    // 归还连接
    public static synchronized void release(Connection conn) {
        //synchronized 线程锁    保证同一时刻只有一个线程用这个方法
        if (conn == null) return;

        try {
            // 1️⃣ 检查连接是否还活着（建议加）
            if (conn.isClosed()) {
                return; // 已经关了，直接丢弃
            }

            if (!conn.isValid(1)) {
                conn.close();//等一秒钟还没确认conn是有效的，关闭后丢弃
                return;
            }

            // 2️⃣ 重置状态
            conn.setAutoCommit(true);

            // 3️⃣ 归还连接池
            pool.addLast(conn);

        } catch (SQLException e) {
            // ❗ 只要出异常 → 直接销毁连接
            try {
                conn.close();
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            }
        }
    }

}
