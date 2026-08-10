package com.itheima.util;

import com.itheima.config.AppConfig;

import java.io.File;
import java.util.logging.*;

public class LogUtil {

    static {
        Level level = parseLevel(AppConfig.getLogLevel());
        String logFile = AppConfig.getLogFile();

        Logger rootLogger = Logger.getLogger("");
        // 移除默认的 ConsoleHandler，避免重复输出
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        rootLogger.setLevel(level);

        try {
            File logFileObj = new File(logFile);
            File logDir = logFileObj.getAbsoluteFile().getParentFile();
            if (logDir != null && !logDir.exists() && !logDir.mkdirs()) {
                System.err.println("fail to create log dir: " + logDir.getAbsolutePath());
            }
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(level);
            rootLogger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SimpleFormatter());
            consoleHandler.setLevel(level);
            rootLogger.addHandler(consoleHandler);
            System.out.println("successfully load logs");
        } catch (Exception e) {
            System.err.println("fail to log " + e.getMessage());
        }
    }

    private static Level parseLevel(String name) {
        try {
            return Level.parse(name.toUpperCase());
        } catch (Exception e) {
            System.err.println("invalid log.level, fallback to INFO: " + name);
            return Level.INFO;
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}
