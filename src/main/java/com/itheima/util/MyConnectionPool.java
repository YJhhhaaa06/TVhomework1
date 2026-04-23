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
        if (conn != null) {
            //如果获取连接时不成功，conn就是null，不会报错
            pool.addLast(conn);
        }
    }
}
