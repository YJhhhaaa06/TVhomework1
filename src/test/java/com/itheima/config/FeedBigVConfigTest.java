package com.itheima.config;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FeedBigVConfig} 单测（feed3-T27-A）：**未启用外部文件**时的静态读语义 / **外部文件覆盖** /
 * **不重启热更** / 节流窗口 / **坏值整批拒绝（不覆盖好值）** / 文件缺失与路径非法一律回落且不抛 /
 * 失败告警按状态迁移只记一条 / 快照不可变。
 *
 * <p><b>隔离手法</b>：全程用 {@link TempDir} 里的真实小文件（不 mock 文件系统）＋反射改写
 * {@code AppConfig.PROPS} 注入键值（沿 {@code AppConfigTest} / {@code FeedBigVRouterTest} 先例，
 * 用例后还原）；**不依赖 DB / Redis / IoC**。热更类用例把 {@code feed.bigv.refreshMillis} 设为 0
 * （每次取值都检查）以消除时序等待。
 */
class FeedBigVConfigTest {

    @TempDir
    Path tempDir;

    private static Properties originalProps;

    private LogProbe probe;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        replaceProps(originalProps);
    }

    @BeforeEach
    void setUp() throws Exception {
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedBigVConfig.class));
        replaceProps(staticProps("7", "100"));      // 基线：未启用外部文件
    }

    @AfterEach
    void tearDown() throws Exception {
        probe.detach();
        replaceProps(originalProps);
    }

    // ==================== 未启用外部文件 ====================

    @Test
    void withoutExternalFileReadsStaticConfigEveryTime() throws Exception {
        FeedBigVConfig config = new FeedBigVConfig();

        assertSnapshot(config.current(), 100, Set.of(7L));

        replaceProps(staticProps("8", "200"));      // 静态配置变化（改造前的既有路径）

        assertSnapshot(config.current(), 200, Set.of(8L),
                "未启用外部文件 ⇒ 每次现读 AppConfig（不缓存）——改造前语义逐字保留");
        assertEquals(0, probe.records().size(), "正常路径不得产生任何日志");
    }

    // ==================== 外部文件覆盖 ====================

    @Test
    void externalFileOverridesStaticConfig() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9, 10\n");
        replaceProps(configProps(file, 5000L));

        FeedBigVConfig config = new FeedBigVConfig();

        assertSnapshot(config.current(), 50, Set.of(9L, 10L),
                "启用外部文件 ⇒ 阈值与名单以文件为准");
    }

    @Test
    void missingKeysInFileFallBackToStaticValues() throws Exception {
        Path file = writeConfig("feed.bigv.userIds=30\n");      // 只给名单、不给阈值
        replaceProps(configProps(file, 0L));

        FeedBigVConfig config = new FeedBigVConfig();

        assertSnapshot(config.current(), 100, Set.of(30L),
                "文件缺键 = 覆盖语义（该键回落静态值），不是半更新");
    }

    // ==================== 热更与节流 ====================

    @Test
    void hotReloadsAfterRefreshWindowWithoutRestart() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 0L));
        FeedBigVConfig config = new FeedBigVConfig();

        assertSnapshot(config.current(), 50, Set.of(9L));

        Files.writeString(file, "feed.bigv.threshold=80\nfeed.bigv.userIds=11\n");

        assertSnapshot(config.current(), 80, Set.of(11L),
                "过窗口即重读 ⇒ 不重启生效，且阈值 / 名单同批换入（无双值撕裂）");
    }

    @Test
    void keepsSnapshotWithinThrottleWindow() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 60_000L));
        FeedBigVConfig config = new FeedBigVConfig();

        assertSnapshot(config.current(), 50, Set.of(9L));

        Files.writeString(file, "feed.bigv.threshold=80\nfeed.bigv.userIds=11\n");

        assertSnapshot(config.current(), 50, Set.of(9L),
                "节流窗口内不重读 ⇒ 窗口即生效延迟上界");
    }

    @Test
    void snapshotTakenEarlierIsNotAffectedByLaterReload() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 0L));
        FeedBigVConfig config = new FeedBigVConfig();

        FeedBigVConfig.Snapshot earlier = config.current();

        Files.writeString(file, "feed.bigv.threshold=80\nfeed.bigv.userIds=11\n");
        config.current();

        assertSnapshot(earlier, 50, Set.of(9L), "已取出的快照是深拷贝，不随后续热更而变");
        assertThrows(UnsupportedOperationException.class, () -> earlier.userIds().add(99L),
                "快照的名单必须不可变");
    }

    // ==================== 坏值不覆盖好值 ====================

    @Test
    void invalidThresholdInFileKeepsPreviousSnapshot() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 0L));
        FeedBigVConfig config = new FeedBigVConfig();
        assertSnapshot(config.current(), 50, Set.of(9L));

        Files.writeString(file, "feed.bigv.threshold=abc\nfeed.bigv.userIds=11\n");

        assertSnapshot(config.current(), 50, Set.of(9L),
                "阈值非法 ⇒ 整批拒绝（名单也不生效），沿用上次快照");

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertInstanceOf(NumberFormatException.class, warnings.getFirst().getThrown(),
                "吸收点即该链唯一捕获点 ⇒ 必须持栈");
    }

    @Test
    void invalidUserIdsInFileKeepsPreviousSnapshot() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 0L));
        FeedBigVConfig config = new FeedBigVConfig();
        assertSnapshot(config.current(), 50, Set.of(9L));

        Files.writeString(file, "feed.bigv.threshold=80\nfeed.bigv.userIds=9,abc\n");

        assertSnapshot(config.current(), 50, Set.of(9L), "名单非法 ⇒ 整批拒绝，沿用上次快照");
        assertEquals(1, probe.atLevel(Level.WARNING).size());
    }

    // ==================== 文件缺失 / 路径非法：回落且不抛 ====================

    @Test
    void missingFileFallsBackToStaticConfigAndWarnsOnce() throws Exception {
        Path absent = tempDir.resolve("not-created.properties");
        replaceProps(configProps(absent, 0L));

        FeedBigVConfig config = new FeedBigVConfig();          // 构造绝不抛

        assertSnapshot(config.current(), 100, Set.of(7L), "文件不存在 ⇒ 回落静态配置");
        assertSnapshot(config.current(), 100, Set.of(7L), "持续失败仍回落静态配置");

        assertEquals(1, probe.atLevel(Level.WARNING).size(),
                "同一失败原因持续存在只记一条（状态迁移触发，非采样限流）");
        assertNotNull(probe.atLevel(Level.WARNING).getFirst().getThrown(), "失败记录持栈");
    }

    @Test
    void recoveryResetsFailureStateSoNextFailureWarnsAgain() throws Exception {
        Path file = writeConfig("feed.bigv.threshold=50\nfeed.bigv.userIds=9\n");
        replaceProps(configProps(file, 0L));
        FeedBigVConfig config = new FeedBigVConfig();
        assertSnapshot(config.current(), 50, Set.of(9L));

        Files.writeString(file, "feed.bigv.threshold=abc\n");           // 失败 1
        config.current();
        assertEquals(1, probe.atLevel(Level.WARNING).size());

        Files.writeString(file, "feed.bigv.threshold=70\nfeed.bigv.userIds=12\n");
        assertSnapshot(config.current(), 70, Set.of(12L), "恢复后按新文件取值");

        Files.writeString(file, "feed.bigv.threshold=abc\n");           // 失败 2（同因，但中间已恢复）
        config.current();
        assertEquals(2, probe.atLevel(Level.WARNING).size(), "恢复后再次失败 ⇒ 重新告警");
    }

    @Test
    void invalidPathOrRefreshValueDoesNotBreakConstruction() throws Exception {
        Properties p = staticProps("7", "100");
        p.setProperty("feed.bigv.configFile", "\u0000bad-path");
        p.setProperty("feed.bigv.refreshMillis", "abc");
        replaceProps(p);

        FeedBigVConfig config = new FeedBigVConfig();          // 构造绝不抛（否则阻断 Tomcat 启动）

        assertSnapshot(config.current(), 100, Set.of(7L), "路径非法 ⇒ 按未启用处理、回落静态配置");
    }

    // ==================== 辅助 ====================

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("bigv.properties");
        Files.writeString(file, content);
        return file;
    }

    private static Properties staticProps(String userIds, String threshold) {
        Properties p = new Properties();
        p.setProperty("feed.bigv.userIds", userIds);
        p.setProperty("feed.bigv.threshold", threshold);
        return p;
    }

    private static Properties configProps(Path file, long refreshMillis) {
        Properties p = staticProps("7", "100");
        p.setProperty("feed.bigv.configFile", file.toString());
        p.setProperty("feed.bigv.refreshMillis", String.valueOf(refreshMillis));
        return p;
    }

    private static void assertSnapshot(FeedBigVConfig.Snapshot snapshot, int threshold, Set<Long> userIds) {
        assertSnapshot(snapshot, threshold, userIds, null);
    }

    private static void assertSnapshot(FeedBigVConfig.Snapshot snapshot, int threshold, Set<Long> userIds,
                                       String message) {
        String suffix = (message == null) ? "" : " —— " + message;
        assertEquals(threshold, snapshot.threshold(), "阈值不符" + suffix);
        assertEquals(userIds, snapshot.userIds(), "名单不符" + suffix);
    }

    private static Properties propsField() throws Exception {
        Field f = AppConfig.class.getDeclaredField("PROPS");
        f.setAccessible(true);
        return (Properties) f.get(null);
    }

    private static void replaceProps(Properties p) throws Exception {
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }
}
