package com.itheima.config;

import com.itheima.ioc.annotation.Component;
import com.itheima.util.LogUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 大V「名单 + 阈值 + 滞回系数」的**取值单点**（feed3-T27-A；feed3-T28-A 并入系数）：判定侧不再直读
 * {@link AppConfig}，一律经本类取**不可变快照**。本类只解决"**值从哪来、何时刷新**"；判定规则仍只在
 * **两处**表达——读侧 {@link com.itheima.feed.service.FeedBigVRouter}（名单 ∪ 状态表 ∪ 阈值）、写侧
 * {@link com.itheima.user.service.AutoBigVStateService}（滞回升降级线）——职责不重叠。
 *
 * <p><b>为什么要有这一层</b>（feed3-T27 拍板，见 NEEDS 4.0）：T26 之后判定单点已统一，但取值仍散调
 * {@code AppConfig}。把取值收敛到本类后，将来把载体从"外部文件"换成"配置表 + 版本轮询"只需换**本类的
 * 实现体**——{@code FeedBigVRouter} 与 fanout / 重建 / 读三处调用方**零改动**（表届时只作**分发通道**，
 * 判定路径仍读内存快照、不读表）。
 *
 * <p><b>载体 = 外部配置文件</b>（"落文件"、不落表；判据见 NEEDS 4.0 T27 段）：
 * <ol>
 *   <li><b>路径配置化</b>（{@link AppConfig#getFeedBigVConfigFile()}，默认**空 = 不启用**）——为了
 *       **生产（本地环境）与测试环境隔离**：跑测试时经 {@code FEED_BIGV_CONFIGFILE} 指向测试清单即可；</li>
 *   <li><b>默认留空 ⇒ 完全不启用外部文件</b>，{@link #current()} 每次现读 {@link AppConfig} 静态值，
 *       行为与 T27-A 之前**完全一致**（含"配置非法照旧向上抛"的 fail-fast 语义与既有 e2e 留证手法）；</li>
 *   <li>文件格式 = {@link Properties}（键名同 {@code app.properties} 的 {@code feed.bigv.threshold} /
 *       {@code feed.bigv.userIds} / {@code feed.bigv.downgradeRatio}）——运维心智一致、零新依赖。</li>
 * </ol>
 *
 * <p><b>热更机制 = 取值时惰性检查 + 节流窗口</b>（不引入后台线程：零线程、零关停、零生命周期管理）：
 * 每次 {@link #current()} 若距上次检查已过 {@link AppConfig#getFeedBigVRefreshMillis()}（默认 5000ms）
 * 才**重新读文件并解析**——**刻意不依赖 mtime**：同一秒内的等长改写（如 {@code 7} → {@code 8}）在
 * 低精度文件系统上 mtime 可能不变，靠 mtime 判变会**漏检**；"过窗口即全量重读"代价只是一次小文件读。
 * 该窗口因此就是**生效延迟上界**（对外不得承诺"改完立即生效"）。
 *
 * <p><b>原子性与坏值</b>：
 * <ul>
 *   <li><b>整批换入</b>——一次读取产出一个 {@link Snapshot}，阈值 / 名单 / 滞回系数**同批**替换，
 *       调用方永远看不到"新名单 + 旧阈值"或"新阈值 + 旧系数"；</li>
 *   <li><b>文件内缺键 = 覆盖语义</b>（该键回落 {@link AppConfig} 静态值），不是半更新——半更新的真正来源
 *       （读到写了一半的文件）由**写入侧**用 {@code 临时文件 + ATOMIC_MOVE} 消除，读侧不加锁；</li>
 *   <li><b>坏值不覆盖好值</b>——文件不存在 / 不可读 / 阈值非数字 / 名单含非法 id / 系数越界
 *       （{@code (0, 1]} 之外）⇒ **整批拒绝**、沿用上次快照（首次则回落静态值），
 *       **绝不产生只更新了一半的快照**。</li>
 * </ul>
 *
 * <p><b>失败告警按"状态迁移"记</b>：同一失败原因持续存在（如路径已配但文件一直没建）只在**首次**记一条
 * {@code WARNING}，恢复成功后重置——这不是日志采样 / 限流，而是避免稳态刷屏；级别取降级型
 * {@code WARNING}（异常已被吸收、对外可用性未受损），但**堆栈仍记在捕获点**（本类是"吸收点即该链唯一
 * 捕获点"，按 {@code 说明书/LOG_CONVENTION.md} §3.1 附加纪律 2 必须持栈）。
 *
 * <p><b>构造绝不抛</b>：IoC 装配期抛异常会阻断 Tomcat 启动（红线"不得让配置异常导致应用起不来"），
 * 故路径非法 / 节流值非法一律降级为默认并告警。
 */
@Component
public class FeedBigVConfig {

    private static final Logger LOGGER = LogUtil.getLogger(FeedBigVConfig.class);

    /** 节流值键缺失 / 非法时的兜底（与 {@code app.properties} 同值）。 */
    private static final long DEFAULT_REFRESH_MILLIS = 5000L;

    /**
     * 一次完整读取的产物：阈值 / 名单 / 滞回系数**同批**换入（构造即深拷贝 + 不可变）——
     * 调用方拿到的快照不会随后续热更而改变。
     *
     * <p>{@code downgradeRatio} 自 feed3-T28-A 起并入本快照：它是**判定输入**（降级线 = 系数 × 阈值），
     * 与阈值同批换入才不会出现"新阈值 + 旧系数"的半更新。
     */
    public record Snapshot(int threshold, Set<Long> userIds, double downgradeRatio) {
        public Snapshot {
            userIds = Collections.unmodifiableSet(new LinkedHashSet<>(userIds));
        }
    }

    /** 外部热更文件路径；{@code null} = **未启用**（键为空或路径非法）。 */
    private final Path file;

    /** 检查节流窗口（毫秒）；{@code 0} = 每次取值都检查（测试用）。 */
    private final long refreshMillis;

    /** 当前快照（不可变对象整体替换）；{@code null} = 外部文件尚未成功加载过。 */
    private volatile Snapshot snapshot;

    /** 上次检查时刻（毫秒），与 {@link #refreshMillis} 共同构成节流。 */
    private volatile long lastCheckMillis;

    /** 上次失败原因（仅锁内读写）：仅用于"失败状态变化才记一条"。 */
    private String lastFailure;

    public FeedBigVConfig() {
        this.file = resolveFile(AppConfig.getFeedBigVConfigFile());
        this.refreshMillis = resolveRefreshMillis();
    }

    /**
     * 取当前快照——**判定侧唯一取值入口**（fanout / 重建 / 读三处经 {@code FeedBigVRouter} 共用）。
     *
     * <p>未启用外部文件时**每次现读** {@link AppConfig}（不缓存）：这既保持与改造前逐字一致的行为，
     * 也让既有单测（反射改写 {@code AppConfig.PROPS} 后立即生效）无需任何改动。
     */
    public Snapshot current() {
        if (file == null) {
            return fromAppConfig();
        }
        reloadIfStale();
        Snapshot current = snapshot;
        // 尚未成功加载过（首读即失败，或节流窗口未到）⇒ 回落静态配置，绝不服务"半成品"
        return (current != null) ? current : fromAppConfig();
    }

    /** 静态配置快照：口径 = 改造前 {@code FeedBigVRouter} 直读 {@link AppConfig}（非法值照旧向上抛）。 */
    private static Snapshot fromAppConfig() {
        return new Snapshot(AppConfig.getFeedBigVThreshold(), AppConfig.getFeedBigVUserIds(),
                AppConfig.getFeedBigVDowngradeRatio());
    }

    // ==================== 内部实现 ====================

    private Path resolveFile(String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            // 构造绝不抛（见类注释）：路径非法 ⇒ 当作"未启用"
            LOGGER.log(Level.WARNING, "大V热更文件路径非法，已忽略（回落静态配置）: " + path, e);
            return null;
        }
    }

    private long resolveRefreshMillis() {
        try {
            return Math.max(0L, AppConfig.getFeedBigVRefreshMillis());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "feed.bigv.refreshMillis 非法，回落 " + DEFAULT_REFRESH_MILLIS + "ms", e);
            return DEFAULT_REFRESH_MILLIS;
        }
    }

    /**
     * 节流检查 + 重载：窗口内直接返回（零 IO、零锁）；过窗口才进锁，**双检**保证并发下只放一个线程进来。
     * 失败同样推进 {@code lastCheckMillis}（坏文件不被高频重试），并沿用上次快照。
     */
    private void reloadIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMillis < refreshMillis) {
            return;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - lastCheckMillis < refreshMillis) {
                return;
            }
            lastCheckMillis = now;
            try {
                snapshot = loadFromFile();
                lastFailure = null;
            } catch (IOException | RuntimeException e) {
                onFailure(e);
            }
        }
    }

    /**
     * 读全文件 + 解析（**一次读取一个快照**）。缺键回落静态值；非法值直接抛 ⇒ 由调用方整批拒绝。
     */
    private Snapshot loadFromFile() throws IOException {
        byte[] bytes = Files.readAllBytes(file);          // 一次读全（写入侧 ATOMIC_MOVE ⇒ 不会读到半份）
        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            props.load(in);
        }

        int threshold = AppConfig.getFeedBigVThreshold();
        Set<Long> userIds = AppConfig.getFeedBigVUserIds();
        double downgradeRatio = AppConfig.getFeedBigVDowngradeRatio();

        String rawThreshold = props.getProperty("feed.bigv.threshold");
        if (rawThreshold != null && !rawThreshold.trim().isEmpty()) {
            threshold = Integer.parseInt(rawThreshold.trim());
        }
        String rawIds = props.getProperty("feed.bigv.userIds");
        if (rawIds != null && !rawIds.trim().isEmpty()) {
            userIds = parseUserIds(rawIds);
        }
        String rawRatio = props.getProperty("feed.bigv.downgradeRatio");
        if (rawRatio != null && !rawRatio.trim().isEmpty()) {
            // 校验口径复用 AppConfig 的单一实现（同包包可见）——静态键与文件键两处规则不漂移
            downgradeRatio = AppConfig.validateFeedBigVDowngradeRatio(Double.parseDouble(rawRatio.trim()));
        }
        return new Snapshot(threshold, userIds, downgradeRatio);
    }

    /** 名单解析口径与 {@link AppConfig#getFeedBigVUserIds()} 一致（跳过空 token；非法 token 抛）。 */
    private static Set<Long> parseUserIds(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("大V热更文件 feed.bigv.userIds 含非法用户 id: " + trimmed, e);
            }
        }
        return ids;
    }

    /**
     * 失败处理：**状态迁移才记一条**（同一原因持续存在不重复刷），带栈
     * （本类是"吸收点即该链唯一捕获点"，LOG_CONVENTION §3.1 附加纪律 2）。
     */
    private void onFailure(Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (reason.equals(lastFailure)) {
            return;
        }
        lastFailure = reason;
        LOGGER.log(Level.WARNING,
                "大V热更文件加载失败，沿用上次快照（首次则回落静态配置）: file=" + file, e);
    }
}
