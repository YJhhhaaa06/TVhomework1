package com.itheima.util;

import com.itheima.config.AppConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 日志工具（周期 T1 / 任务 log-01：输出层 + 环境层）。
 *
 * <p>初始化时机与既有语义不变：**首个触达本类的线程**在其静态块里完成装配——先清空 JUL root
 * 既有 handler，再挂"控制台 + N 个文件输出端"。{@link #getLogger(Class)} 的签名与语义**不变**
 * （23 个业务类按类名取 logger，本次改造零改动；原记 24，T2 窗口实测修正）。
 *
 * <p>输出层三件事：
 *
 * <ol>
 *   <li><b>单行结构化</b>：Console 与所有文件输出端共用同一个 {@link LogFormatter}。</li>
 *   <li><b>可扩展多输出端</b>（NEEDS 4.0 D9）：分流按「输出端规格表（{@link #resolveFileOutputs()}）
 *       + 配置键」组织，**流程内不出现字面量文件名**——将来新增输出端（如第二张清单的审计日志
 *       {@code audit.log}）只需在规格表加一项、{@code app.properties} 加一个配置键，持有 LOGGER 的
 *       业务类零改动。默认两个文件输出端：{@code system}（全域，阈值 {@code log.level}）与
 *       {@code error}（只收 {@code >= log.error.level}，默认 SEVERE，便于排障速览）。</li>
 *   <li><b>按大小轮转</b>：由 JUL 原生 {@code FileHandler(pattern, limit, count, append)} 承担
 *       （阈值/保留个数来自 {@code log.maxBytes} / {@code log.fileCount}，参数化可配）。JUL 语义：
 *       文件名为 {@code <log.file>.<N>}，**N=0 即当前写入文件、N 越大越旧**，超出保留个数的最旧文件
 *       被删除；轮转发生在记录写完之后，故单文件占用会略超阈值。</li>
 * </ol>
 *
 * <p>环境层（路径口径）：{@code log.file} 所在目录即"日志目录"；**其余输出端的相对路径只取文件名
 * 并落同一目录**。于是"改写 {@code log.file}（或注入 {@code LOG_PATH} 别名，见
 * {@code tools/run_tests.py} 与 {@code pom.xml} surefire 配置）"一处即可让**所有**日志文件同时换
 * 目录——既有 N5（测试日志写进运行日志目录）的隔离不需要为每个输出端各配一个环境变量。
 * 相对路径仍按进程 cwd 解析（沿用既有语义），解析结果会以绝对路径写进启动日志，便于定位。
 * {@code log.file} 为空（配置缺失 / {@code LOG_PATH} 被置空）时**整组文件输出端跳过**、降级为仅
 * 控制台——不猜目录，避免把其余输出端写到"日志目录"之外。
 */
public class LogUtil {

    /** 单行结构化格式器：Console 与全部文件输出端共用的唯一实例（无状态，可安全共享）。 */
    private static final Formatter FORMATTER = new LogFormatter();

    /** 文件输出端落盘编码：显式钉 UTF-8，不依赖 JVM 默认字符集（中文日志跨环境一致）。 */
    private static final String ENCODING = "UTF-8";

    static {
        Logger rootLogger = Logger.getLogger("");
        // 移除 root 上的既有 handler（含 JDK 默认 ConsoleHandler），改由本工具的
        // 输出端统一承接，避免重复输出；副作用是同一 JVM 内的容器/第三方
        // JUL 日志也会路由到这些 handler（本类初始化前已存在的 handler 一律不保留）
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }

        // 初始化日志必须"先有通道再打记录"：root 的默认 handler 已移除，
        // 此时任何早于本行的日志都会因无 handler 承接而被丢弃
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(FORMATTER);
        rootLogger.addHandler(consoleHandler);

        Logger initLogger = getLogger(LogUtil.class);

        Level rootLevel = parseLevel(initLogger, AppConfig.getLogLevel(), Level.INFO);
        rootLogger.setLevel(rootLevel);
        consoleHandler.setLevel(rootLevel);

        int maxBytes = AppConfig.getLogMaxBytes();
        int fileCount = AppConfig.getLogFileCount();
        for (LogOutput output : resolveFileOutputs()) {
            installFileOutput(rootLogger, initLogger, output, maxBytes, fileCount);
        }
    }

    /**
     * 输出端规格表：**"配置 → 输出端"的唯一解析点**，也是 D9 的扩展点（新增输出端只动这里 + 配置）。
     *
     * <p>目录口径见类注释：相对路径只取文件名并锚到 {@code log.file} 所在目录，绝对路径原样使用。
     * 包可见，供单测直接断言"文件名来自配置、不硬编码"。
     */
    static List<LogOutput> resolveFileOutputs() {
        String primaryPath = AppConfig.getLogFile();
        List<LogOutput> outputs = new ArrayList<>();
        if (primaryPath.isEmpty()) {
            // log.file 为空（键缺失 / LOG_PATH 被置空）→ **整组文件输出端跳过**，降级为仅控制台。
            // 不能只跳过主输出：目录口径以 log.file 为锚，空串会让 new File("") 解析到 cwd，
            // 其余输出端会落到"日志目录"之外（N5 类事故就是日志跑到意外目录）。
            return outputs;
        }
        File primary = new File(primaryPath);
        File logDir = primary.getAbsoluteFile().getParentFile();

        outputs.add(new LogOutput("system", primary, AppConfig.getLogLevel(), Level.INFO));

        File errorFile = new File(AppConfig.getLogErrorFile());
        if (!errorFile.isAbsolute() && logDir != null) {
            errorFile = new File(logDir, errorFile.getName());
        }
        outputs.add(new LogOutput("error", errorFile, AppConfig.getLogErrorLevel(), Level.SEVERE));
        return outputs;
    }

    /**
     * 按大小轮转的文件 Handler：{@code maxBytes <= 0} 视为"不轮转"，此时强制 {@code count=1}
     * （JUL 仅在 count 大于 1 时追加 {@code .N} 序号）→ 文件名精确等于配置值；否则交由 JUL 原生
     * limit/count 轮转。包可见，供单测断言文件名模式与轮转参数。
     */
    static FileHandler createFileHandler(File file, int maxBytes, int fileCount) throws IOException {
        if (maxBytes <= 0) {
            return new FileHandler(file.getPath(), true);
        }
        return new FileHandler(file.getPath(), maxBytes, Math.max(1, fileCount), true);
    }

    /** 装配单个文件输出端；任一路不可用（目录不可写/被占用等）只降级这一路，不影响日志系统本身。 */
    private static void installFileOutput(Logger rootLogger, Logger initLogger, LogOutput output,
                                          int maxBytes, int fileCount) {
        try {
            File file = output.file();
            File logDir = file.getAbsoluteFile().getParentFile();
            if (logDir != null && !logDir.exists() && !logDir.mkdirs()) {
                initLogger.warning("fail to create log dir: " + logDir.getAbsolutePath());
            }
            FileHandler handler = createFileHandler(file, maxBytes, fileCount);
            handler.setFormatter(FORMATTER);
            handler.setEncoding(ENCODING);
            handler.setLevel(parseLevel(initLogger, output.levelName(), output.defaultLevel()));
            rootLogger.addHandler(handler);
            initLogger.info("successfully load logs: " + output.name() + " -> " + file.getAbsolutePath());
        } catch (Exception e) {
            initLogger.log(Level.SEVERE, "fail to log " + output.name() + ": " + e.getMessage(), e);
        }
    }

    private static Level parseLevel(Logger initLogger, String name, Level fallback) {
        try {
            return Level.parse(name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            initLogger.warning("invalid log level, fallback to " + fallback.getName() + ": " + name);
            return fallback;
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }

    /** 文件输出端规格：{@code name} 用于启动日志与失败提示，{@code file} 为落盘文件（已解析目录口径）。 */
    record LogOutput(String name, File file, String levelName, Level defaultLevel) {
    }
}
