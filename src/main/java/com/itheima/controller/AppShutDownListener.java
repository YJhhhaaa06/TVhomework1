package com.itheima.controller;

import com.itheima.util.MyConnectionPool;
import com.itheima.util.MyRedisPool;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class AppShutDownListener implements ServletContextListener {

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // 清除 Redis 缓存数据
        MyRedisPool.flushDb();

        // 关闭 Redis
        MyRedisPool.close();

        // 关闭数据库
        MyConnectionPool.closePool();

        System.out.println("资源已释放");
    }
}
