package com.itheima.util;

import com.itheima.config.AppConfig;

import java.io.File;
import java.util.logging.*;

public class LogUtil {

    static {
        Logger rootLogger = Logger.getLogger("");
        // 移除 root 上的既有 handler（含 JDK 默认 ConsoleHandler），改由本工具的
        // Console + File 统一承接，避免重复输出；副作用是同一 JVM 内的容器/第三方
        // JUL 日志也会路由到这两个 handler（本类初始化前已存在的 handler 一律不保留）
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }

        // 初始化日志必须"先有通道再打记录"：root 的默认 handler 已移除，
        // 此时任何早于本行的日志都会因无 handler 承接而被丢弃
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new SimpleFormatter());
        rootLogger.addHandler(consoleHandler);

        Logger initLogger = getLogger(LogUtil.class);

        Level level = parseLevel(initLogger, AppConfig.getLogLevel());
        rootLogger.setLevel(level);
        consoleHandler.setLevel(level);

        String logFile = AppConfig.getLogFile();
        try {
            File logFileObj = new File(logFile);
            File logDir = logFileObj.getAbsoluteFile().getParentFile();
            if (logDir != null && !logDir.exists() && !logDir.mkdirs()) {
                initLogger.warning("fail to create log dir: " + logDir.getAbsolutePath());
            }
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(level);
            rootLogger.addHandler(fileHandler);
            initLogger.info("successfully load logs");
        } catch (Exception e) {
            // 文件日志不可用（目录不可写/被占用等）时降级为仅控制台，不影响日志系统本身
            initLogger.log(Level.SEVERE, "fail to log " + e.getMessage(), e);
        }
    }

    private static Level parseLevel(Logger initLogger, String name) {
        try {
            return Level.parse(name.toUpperCase());
        } catch (Exception e) {
            initLogger.warning("invalid log.level, fallback to INFO: " + name);
            return Level.INFO;
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}
