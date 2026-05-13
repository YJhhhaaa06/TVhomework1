package com.itheima.controller;

import com.itheima.ioc.IocContainer;
import com.itheima.service.ContentService;
import com.itheima.util.MyConnectionPool;
import com.itheima.util.MyRedisPool;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class AppShutDownListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        IocContainer.getInstance().init();
        System.out.println("IOC 容器初始化完成");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        IocContainer.getInstance().getBean(ContentService.class).shutdown();

        MyRedisPool.flushDb();
        MyRedisPool.close();
        MyConnectionPool.closePool();

        System.out.println("资源已释放");
    }
}
