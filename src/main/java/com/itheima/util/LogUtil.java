package com.itheima.util;

import java.util.logging.*;

public class LogUtil {

    static {
        Logger rootLogger = Logger.getLogger("");
        // 移除默认的 ConsoleHandler，避免重复输出
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        rootLogger.setLevel(Level.INFO);

        try {
            FileHandler fileHandler = new FileHandler("logs/system.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.INFO);
            rootLogger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SimpleFormatter());
            consoleHandler.setLevel(Level.INFO);
            rootLogger.addHandler(consoleHandler);
        } catch (Exception e) {
            System.err.println("fail to log " + e.getMessage());
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}