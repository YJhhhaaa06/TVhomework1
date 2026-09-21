package com.itheima.util;

import com.itheima.config.AppConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1（log-01）日志底座单测：输出端规格取自配置（不硬编码文件名）、相对路径锚到日志目录、
 * 按大小轮转的文件名模式与保留个数（方向与语义由 temp_script 一次性探针核验）、root 装配
 * （1 控制台 + 2 文件输出端）、{@code getLogger} 签名与语义不变。
 *
 * <p>不碰真实 Redis / DB / HTTP；文件落盘一律用 {@link TempDir}。
 */
class LogUtilTest {

    /** 类加载时的原始 PROPS **内容副本**（改配置的用例结束后还原，避免污染其它测试类，同 AppConfigTest 思路）。 */
    private static Properties originalProps;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        // 先触达静态块：root 装配只在首次类初始化时执行一次，必须在任何用例改写 PROPS **之前**
        // 用真实配置完成（否则装配会跟着探针配置走，装出与生产无关的输出端）。
        LogUtil.getLogger(LogUtilTest.class);
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        replaceProps(originalProps);
    }

    // ==================== 红线：getLogger 签名与语义不变 ====================

    @Test
    void getLoggerKeepsClassBasedNameAndInstance() {
        assertSame(Logger.getLogger(LogUtilTest.class.getName()), LogUtil.getLogger(LogUtilTest.class));
    }

    // ==================== 输出端规格：文件名来自配置（D9 不硬编码） ====================

    @Test
    void fileOutputsTakeFileNamesFromConfig(@TempDir Path dir) throws Exception {
        File probePrimary = dir.resolve("verify/logs/system.log").toFile();
        Properties probe = new Properties();
        probe.setProperty("log.file", probePrimary.getPath());
        probe.setProperty("log.level", "WARNING");
        probe.setProperty("log.error.file", "other.log");
        probe.setProperty("log.error.level", "WARNING");
        probe.setProperty("log.access.file", "probe.log");
        replaceProps(probe);
        try {
            List<LogUtil.LogOutput> outputs = LogUtil.resolveFileOutputs();

            assertEquals(3, outputs.size(), "应有 system / error / access 三个文件输出端");
            assertEquals("system", outputs.get(0).name());
            assertEquals(normalized(probePrimary), normalized(outputs.get(0).file()),
                    "system 输出端应使用配置的 log.file（同一路径必须能从配置复现 → 非字面量硬编码）");
            assertEquals("WARNING", outputs.get(0).levelName(), "级别名应取自配置的 log.level");
            assertEquals(Level.INFO, outputs.get(0).defaultLevel(), "system 输出端的级别兜底为 INFO");

            assertEquals("error", outputs.get(1).name());
            assertEquals("other.log", outputs.get(1).file().getName(),
                    "error 输出端文件名应使用配置的 log.error.file");
            assertEquals("WARNING", outputs.get(1).levelName(), "级别名应取自配置的 log.error.level");
            assertEquals(Level.SEVERE, outputs.get(1).defaultLevel(), "error 输出端的级别兜底为 SEVERE");
            assertEquals(normalized(outputs.get(0).file().getParentFile()),
                    normalized(outputs.get(1).file().getParentFile()),
                    "相对文件名应锚到 log.file 所在目录");

            assertEquals("access", outputs.get(2).name(), "T3 起第三个输出端为 access");
            assertEquals("probe.log", outputs.get(2).file().getName(),
                    "access 输出端文件名应使用配置的 log.access.file（不硬编码）");
            assertEquals("INFO", outputs.get(2).levelName(), "access 输出端级别固定 INFO");
            assertEquals(Level.INFO, outputs.get(2).defaultLevel());
            assertEquals(LogUtil.ACCESS_LOGGER_NAME, outputs.get(2).owner(),
                    "access 输出端应挂专属 logger（owner 区分挂载目标，不挂 root）");
            assertEquals(normalized(outputs.get(0).file().getParentFile()),
                    normalized(outputs.get(2).file().getParentFile()),
                    "access 相对文件名同样锚到 log.file 所在目录");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void absoluteErrorFileIsUsedAsConfigured(@TempDir Path dir) throws Exception {
        File probeError = dir.resolve("elsewhere/error.log").toFile();
        Properties probe = new Properties();
        probe.setProperty("log.file", dir.resolve("logs/system.log").toFile().getPath());
        probe.setProperty("log.error.file", probeError.getPath());
        replaceProps(probe);
        try {
            List<LogUtil.LogOutput> outputs = LogUtil.resolveFileOutputs();

            assertEquals(normalized(probeError), normalized(outputs.get(1).file()),
                    "绝对路径应按配置原样使用，不被日志目录改写");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void fileOutputsSkippedWhenPrimaryPathBlank() throws Exception {
        Properties probe = new Properties();
        probe.setProperty("log.file", "   "); // get 会 trim → 空串
        replaceProps(probe);
        try {
            assertTrue(LogUtil.resolveFileOutputs().isEmpty(),
                    "log.file 为空时整组文件输出端跳过（降级为仅控制台），"
                            + "不得让其余输出端落到日志目录之外");
        } finally {
            replaceProps(originalProps);
        }
    }

    @Test
    void errorOutputLandsBesideConfiguredSystemLog() {
        List<LogUtil.LogOutput> outputs = LogUtil.resolveFileOutputs();

        assertEquals(3, outputs.size(), "配置可用时应装配 system / error / access 三个文件输出端");
        assertEquals(AppConfig.getLogErrorFile(), outputs.get(1).file().getName(),
                "error 输出端文件名应来自配置");
        assertEquals("access", outputs.get(2).name(), "第三个输出端固定为 access");
        assertEquals(normalized(outputs.get(0).file().getParentFile()),
                normalized(outputs.get(1).file().getParentFile()),
                "改写 log.file（测试侧即 LOG_PATH）一处即可让所有日志文件落同目录（N5 隔离）");
        assertEquals(normalized(outputs.get(2).file().getParentFile()),
                normalized(outputs.get(0).file().getParentFile()),
                "access 输出端与 system/error 同目录（一次 LOG_PATH 全部换目录）");
    }

    @Test
    void rotationAndErrorParamsComeFromAppProperties() {
        ResourceBundle shipped = ResourceBundle.getBundle("app");

        assertEquals(shipped.getString("log.error.file"), AppConfig.getLogErrorFile());
        assertEquals(shipped.getString("log.error.level"), AppConfig.getLogErrorLevel());
        assertEquals(Integer.parseInt(shipped.getString("log.maxBytes")), AppConfig.getLogMaxBytes());
        assertEquals(Integer.parseInt(shipped.getString("log.fileCount")), AppConfig.getLogFileCount());
        assertEquals(shipped.getString("log.access.file"), AppConfig.getLogAccessFile(), "access 文件名来自配置");
        assertEquals(Integer.parseInt(shipped.getString("log.slowRequestMs")), AppConfig.getLogSlowRequestMs(),
                "慢请求阈值来自配置（默认 1000ms）");
        assertTrue(AppConfig.getLogMaxBytes() > 0, "出厂配置应开启按大小轮转（N4：单文件无限增长）");
        assertTrue(AppConfig.getLogFileCount() >= 1, "保留个数至少 1（含当前写入文件）");
    }

    // ==================== 轮转：文件名模式 / 保留个数 / 方向 ====================

    @Test
    void rotationNamesFilesByGenerationAndRecyclesOldest(@TempDir Path dir) throws Exception {
        File base = dir.resolve("system.log").toFile();
        // 每条记录都远超阈值 → 每次写入都触发一次轮转，终态完全确定（不依赖字节数算术）
        FileHandler handler = LogUtil.createFileHandler(base, 100, 3);
        try {
            publish(handler, "AAA");
            publish(handler, "BBB");
            publish(handler, "CCC");
        } finally {
            handler.close();
        }

        assertEquals(List.of("system.log.0", "system.log.1", "system.log.2"), fileNames(dir),
                "轮转文件名应为 <文件>.<N>，且文件个数不超过 log.fileCount（最旧一代被回收）");

        Path gen1 = dir.resolve("system.log.1");
        Path gen2 = dir.resolve("system.log.2");
        assertTrue(read(gen1).contains("CCC"), "generation 1 应持有最近一次轮转出去的内容（generation 0 为当前写入文件）");
        assertTrue(read(gen2).contains("BBB"), "generation 2 应为更旧一代");
        assertFalse(read(gen2).contains("AAA"), "已被回收的最旧一代不得残留");
    }

    @Test
    void noRotationKeepsExactConfiguredFileName(@TempDir Path dir) throws Exception {
        File base = dir.resolve("app.log").toFile();
        FileHandler handler = LogUtil.createFileHandler(base, 0, 5);
        try {
            publish(handler, "single");
        } finally {
            handler.close();
        }

        assertEquals(List.of("app.log"), fileNames(dir),
                "maxBytes<=0 视为不轮转：文件名应精确等于配置值，不得追加 .N 序号");
        assertTrue(read(dir.resolve("app.log")).contains("single"));
    }

    // ==================== root 装配（多输出端） ====================

    /** 装配只在本 JVM 首次触达 {@link LogUtil} 时发生一次，{@link #saveOriginalProps()} 已保证那一次用的是真实配置。 */
    @Test
    void rootLoggerWiresConsoleAndTwoFileOutputs() {
        List<Handler> handlers = Arrays.asList(Logger.getLogger("").getHandlers());
        List<FileHandler> fileHandlers = new ArrayList<>();
        int consoles = 0;
        for (Handler handler : handlers) {
            if (handler instanceof ConsoleHandler) {
                consoles++;
            } else if (handler instanceof FileHandler fileHandler) {
                fileHandlers.add(fileHandler);
            }
        }

        // T3：root 仍只挂 system/error 两个文件输出端；access 挂专属 logger（见 accessLoggerWiresDedicatedFileHandler），
        // 故此处断言不变——access 行不得混入 root 全量 handler（否则 system.log / 控制台都会被"每请求一行"灌满）
        assertEquals(1, consoles, "控制台输出端应有且仅有一个: " + handlers);
        assertEquals(2, fileHandlers.size(), "root 应装配 system 与 error 两个文件输出端: " + handlers);
        assertTrue(fileHandlers.stream().anyMatch(h -> Level.SEVERE.equals(h.getLevel())),
                "error 输出端级别应来自 log.error.level（默认 SEVERE）: " + fileHandlers);
        for (Handler handler : handlers) {
            assertTrue(handler.getFormatter() instanceof LogFormatter,
                    "各输出端应统一使用 LogFormatter: " + handler);
        }
    }

    /** T3：access 输出端挂专属 logger、level INFO、关闭向 root 传播（access 行只落 access.log）。 */
    @Test
    void accessLoggerWiresDedicatedFileHandler() {
        Logger access = LogUtil.getAccessLogger();

        assertFalse(access.getUseParentHandlers(), "access 记录不得传播到 root（不进 system.log/控制台）");
        assertEquals(Level.INFO, access.getLevel(), "access logger 级别固定 INFO");
        Handler[] handlers = access.getHandlers();
        assertEquals(1, handlers.length, "access logger 应恰好挂一个文件输出端: " + Arrays.toString(handlers));
        assertTrue(handlers[0] instanceof FileHandler, "access 输出端应为 JUL FileHandler（轮转/编码同构）");
        assertTrue(handlers[0].getFormatter() instanceof LogFormatter, "access 输出端应统一使用 LogFormatter");
    }

    // ==================== 工具 ====================

    /** 每条记录都超阈值（消息填充到远超 limit），用于"每次写入即轮转"的确定性场景。 */
    private static void publish(FileHandler handler, String marker) {
        handler.publish(new LogRecord(Level.INFO, marker + "-" + "x".repeat(150)));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String normalized(File file) {
        try {
            return file.getCanonicalFile().getPath().replace('\\', '/');
        } catch (IOException e) {
            throw new IllegalStateException("解析规范路径失败: " + file, e);
        }
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static Properties propsField() throws Exception {
        Field field = AppConfig.class.getDeclaredField("PROPS");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static void replaceProps(Properties properties) throws Exception {
        // PROPS 为 static final，不能替换字段引用（JDK 17+ 强封装）；改为改写其 map 内容
        // （同 AppConfigTest 的 replaceProps 口径）。
        Properties current = propsField();
        current.clear();
        current.putAll(properties);
    }
}
