package com.itheima.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 应用配置：classpath 加载 app.properties，外部覆盖两级（优先级从高到低）：
 *   1. 环境变量（key 去点转大写，例：db.password -> DB_PASSWORD；log.file 额外支持 LOG_PATH 别名）
 *   2. JVM 系统属性（-Dkey=value，T18：与 Tomcat context.xml 的 ${key:-default} 占位符同源，
 *      保证 /upload 静态挂载与上传落盘两侧一致）
 * 配置文件缺失或解析失败时启动即失败（fail-fast）。
 */
public final class AppConfig {

    private static final Properties PROPS = load();

    private AppConfig() {
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/app.properties")) {
            if (in == null) {
                throw new IllegalStateException("app.properties 不存在于 classpath，无法启动");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载 app.properties 失败", e);
        }

        for (String key : props.stringPropertyNames()) {
            String envName = key.replace('.', '_').toUpperCase();
            String value = System.getenv(envName);
            if (value == null && "log.file".equals(key)) {
                value = System.getenv("LOG_PATH");
            }
            if (value == null) {
                value = System.getProperty(key);   // T18：-Dkey=value 系统属性覆盖（与 context.xml ${key:-default} 同源）
            }
            if (value != null) {
                props.setProperty(key, value.trim());
            }
        }
        return props;
    }

    private static String get(String key) {
        return PROPS.getProperty(key, "").trim();
    }

    // ===== 带默认值读取（T18）：键缺失/值为空时返回默认值；键存在但数值解析失败照旧抛（fail-fast）。
    //      既有无默认值 getter 语义不变。包可见（同包 getter/测试可用）。

    static String get(String key, String defaultValue) {
        String v = PROPS.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? defaultValue : v.trim();
    }

    static int getInt(String key, int defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Integer.parseInt(v);
    }

    static long getLong(String key, long defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Long.parseLong(v);
    }

    static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : Boolean.parseBoolean(v);
    }

    private static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    private static long getLong(String key) {
        return Long.parseLong(get(key));
    }

    // ===== 数据库 =====

    public static String getDbDriver() {
        return get("db.driver");
    }

    public static String getDbUrl() {
        return get("db.url");
    }

    public static String getDbUsername() {
        return get("db.username");
    }

    public static String getDbPassword() {
        return get("db.password");
    }

    public static int getDbInitSize() {
        return getInt("db.pool.initSize");
    }

    public static int getDbMaxSize() {
        return getInt("db.pool.maxSize");
    }

    public static long getDbTimeoutMs() {
        return getLong("db.pool.timeoutMs");
    }

    // ===== Redis =====

    public static String getRedisHost() {
        return get("redis.host");
    }

    public static int getRedisPort() {
        return getInt("redis.port");
    }

    public static int getRedisMaxTotal() {
        return getInt("redis.maxTotal");
    }

    public static int getRedisMaxIdle() {
        return getInt("redis.maxIdle");
    }

    public static int getRedisMinIdle() {
        return getInt("redis.minIdle");
    }

    // T1（cache-01）：显式超时 + 熔断参数（治 U-10 未配置超时 / N1 逐请求等连接超时）

    public static int getRedisConnectTimeoutMs() {
        return getInt("redis.connectTimeoutMs");
    }

    public static int getRedisSoTimeoutMs() {
        return getInt("redis.soTimeoutMs");
    }

    public static long getRedisPoolMaxWaitMs() {
        return getLong("redis.pool.maxWaitMs");
    }

    public static int getRedisBreakerFailureThreshold() {
        return getInt("redis.breaker.failureThreshold");
    }

    public static long getRedisBreakerCooldownMillis() {
        return getLong("redis.breaker.cooldownMillis");
    }

    // ===== RabbitMQ（T16 feed1-16：连接基础配置；连接管理/拓扑/消费框架见 T17）=====

    // 与 db.*/redis.* 的 fail-fast 不同，MQ 为"可降级外部依赖"（不可用不得阻断启动），
    // 故统一带默认值读取——键缺失/为空取本地容器约定值（容错口径对齐 log.*）。
    // 默认值与 app.properties 保持一致：本机容器 rabbitmq / 5672（非 RabbitMQ 出厂 guest/guest）。

    public static String getRabbitmqHost() {
        return get("rabbitmq.host", "localhost");
    }

    public static int getRabbitmqPort() {
        return getInt("rabbitmq.port", 5672);
    }

    public static String getRabbitmqUsername() {
        return get("rabbitmq.username", "admin");
    }

    public static String getRabbitmqPassword() {
        return get("rabbitmq.password", "admin123");
    }

    /** AMQP vhost（容器默认 "/"）。 */
    public static String getRabbitmqVhost() {
        return get("rabbitmq.vhost", "/");
    }

    /**
     * 连接 / 握手超时（毫秒，T17 feed1-17）。
     *
     * <p>必须封顶：{@code ConnectionFactory} 默认连接超时 60s，broker 不可用时会把
     * Tomcat 启动线程阻塞 60s，违反"MQ 不可用不得阻断启动"。
     */
    public static int getRabbitmqConnectionTimeoutMs() {
        return getInt("rabbitmq.connection.timeoutMs", 2000);
    }

    // ===== JWT =====

    public static String getJwtSecret() {
        return get("jwt.secret");
    }

    public static long getJwtExpireMillis() {
        return getLong("jwt.expireHours") * 60 * 60 * 1000;
    }

    // ===== 文件上传 =====

    public static String getUploadPath() {
        return get("upload.path");
    }

    public static long getUploadMaxSize() {
        return getLong("upload.maxSize");
    }

    // ===== 缓存 =====

    public static long getContentTtlMillis() {
        return getLong("cache.content.ttlMinutes") * 60 * 1000;
    }

    public static long getCommentTtlSeconds() {
        return getLong("cache.comment.ttlMinutes") * 60;
    }

    public static long getLikeTtlSeconds() {
        return getLong("cache.like.ttlMinutes") * 60;
    }

    public static long getFollowTtlSeconds() {
        return getLong("cache.follow.ttlMinutes") * 60;
    }

    /**
     * 写扩散收件箱 TTL（feed1-18）：键 {@code feed.inbox.ttlMinutes}（默认 60 分钟）。
     *
     * <p>**带默认值**：收件箱是影子期派生副本（可丢、靠重建自愈），配置缺失不得让应用起不来
     * （对齐 redis.* 与日志配置的容错口径；键存在但值非法照旧抛，fail-fast 语义不变）。
     *
     * <p>TTL 口径 = **整条收件箱**的活跃期（不是单条内容），读命中 / fanout 写入均滑动续期；
     * 到期等价于"该用户关注的人近期未发内容且本人未读" → 整条回收，下次读或下次 fanout 重建。
     */
    public static long getFeedInboxTtlSeconds() {
        return getLong("feed.inbox.ttlMinutes", 60L) * 60;
    }

    // ===== feed 二期窗口重算（feed2-22 T22）=====

    /**
     * 窗口重算「每关注作者保留条数」K（feed2-22 T22）：键 {@code feed.inbox.windowPerAuthor}（默认 20）。
     *
     * <p>口径（NEEDS 4.0 机制拍板）：重建按**每关注作者最近 K 条**取样，再归并去重 →
     * contentId 降序 → 裁剪到 {@link #getFeedInboxWindowMax()}；这把 T19 的"全量重算"
     * （内存 ∝ 关注规模 × 内容量）改为**有界窗口**（内存 ∝ 关注数 × K），并保证长尾作者
     * 不被高产作者挤空。
     *
     * <p>**带默认值（20）**：窗口参数偏离散取值的容错口径，键缺失不得让应用起不来
     * （同 {@link #getFeedInboxTtlSeconds()}；键存在但值非法照旧抛，fail-fast 语义不变）。
     * 阈值机制细化 / 活跃用户策略属三期。
     */
    public static int getFeedInboxWindowPerAuthor() {
        return getInt("feed.inbox.windowPerAuthor", 20);
    }

    /**
     * 窗口重算「总窗口上限」C（feed2-22 T22）：键 {@code feed.inbox.windowMax}（默认 200）。
     *
     * <p>归并去重后按 contentId 降序截断到 C——重建产物（{@code feed_inbox} 窗口 + 同步状态）
     * 与读数均以此为界；**读侧的页级有界（总窗口 M）属 T23**，两者口径各自独立。
     * 缺省值 200 与批量尺寸（{@code feed.fanout.batch} 默认 200 / {@code feed.rebuild.authorBatch} 默认 50）同量级。
     *
     * <p>⚠️ **取值应保持在合理量级**：C 同时决定窗口落库的单条多行 `INSERT` 行数（占位符 = 2C）
     * 与重建的内存峰值 ⇒ 误配成极大值会逼近 `max_allowed_packet` / 占位符上限而整体降级；
     * 本方法不做上界校验（与既有数值配置的"缺省容错、非法抛"口径一致），由配置方自担。
     */
    public static int getFeedInboxWindowMax() {
        return getInt("feed.inbox.windowMax", 200);
    }

    // ===== feed 二期大V占位路由（feed2-21 T21）=====

    /**
     * 大V判定阈值（feed2-21 T21，**占位机制**）：作者粉丝数 ≥ 本值 ⇒ 视为大V。
     *
     * <p>**带默认值（10000）**：占位路由的可选参数，键缺失不得让应用起不来（对齐
     * {@link #getFeedInboxTtlSeconds()} 的容错口径；键存在但值非法照旧抛，fail-fast 语义不变）。
     * 阈值上下波动 / 掉粉补推等机制归三期。
     */
    public static int getFeedBigVThreshold() {
        return getInt("feed.bigv.threshold", 10000);
    }

    /**
     * 大V名单（feed2-21 T21，**占位机制**）：逗号分隔的用户 id 列表；键缺失 / 为空 → 空集合。
     *
     * <p>名单 = 显式指定的大V（不经粉丝数阈值判定，与阈值取"或"）；token 前后空白跳过（末位空项合法）。
     * **非法 token 抛 {@link IllegalArgumentException}**（fail-fast，与既有数值解析失败口径一致，
     * 参数名进消息便于定位）；名单维护机制归三期。
     */
    public static Set<Long> getFeedBigVUserIds() {
        Set<Long> ids = new LinkedHashSet<>();
        String raw = get("feed.bigv.userIds");
        if (raw.isEmpty()) {
            return ids;
        }
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("feed.bigv.userIds 含非法用户 id: " + t, e);
            }
        }
        return ids;
    }

    /**
     * 大V批量判定的**单条 SQL 批量尺寸**（feed3-T26）：键 {@code feed.bigv.queryBatch}（默认 200）。
     *
     * <p><b>用途</b>：{@link com.itheima.feed.service.FeedBigVRouter#isBigVBatch(java.util.List)} 把待判作者
     * 的 IN 列表按本值切分为多条 SQL——关注数无上限（重建 / 读侧都会带上整个关注集），单条巨型 IN 会
     * 放大语句长度与占位符个数；默认 200 与 {@link #getFeedFanoutBatch()}（200 = fanout 粉丝窗口批量）
     * 及 {@link #getFeedInboxWindowMax()} 同量级。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 默认 200，不得让应用起不来（同 {@link #getFeedBigVThreshold()}）；
     * 键存在但非数字照旧抛（fail-fast）。**取值必须为正数**——非正会让分块循环无法前进，
     * 故由调用方在进入 fail-open 捕获之前校验并抛 {@link IllegalArgumentException}（见 {@code FeedBigVRouter}）。
     *
     * <p><b>与 T29 的边界</b>：本键属 T26 任务内生需求，标为**"已参数化、不重复"**；
     * T29「feed 域批量尺寸参数化」**已落地（feed3-T29）**，其范围 = 新增
     * {@link #getFeedFanoutBatch()}（{@code feed.fanout.batch}）与
     * {@link #getFeedRebuildAuthorBatch()}（{@code feed.rebuild.authorBatch}）。
     */
    public static int getFeedBigVQueryBatch() {
        return getInt("feed.bigv.queryBatch", 200);
    }

    // ===== feed 三期大V名单 / 阈值热更（feed3-T27-A）=====

    /**
     * 大V名单 / 阈值**外部热更文件**路径（feed3-T27-A）：键 {@code feed.bigv.configFile}（**默认空 = 不启用**）。
     *
     * <p><b>语义</b>：非空 ⇒ 由 {@link FeedBigVConfig} 读取该文件（Properties 格式，键名与本档
     * {@code feed.bigv.threshold} / {@code feed.bigv.userIds} 一致）并在**不重启进程**的前提下热更；
     * **空 ⇒ 完全不启用外部文件**，判定值退回本类静态读取（{@link #getFeedBigVThreshold()} /
     * {@link #getFeedBigVUserIds()}）——默认留空保证既有跑法（含 e2e 用环境变量覆盖的留证手法）**零影响**。
     *
     * <p><b>为什么路径也配置化</b>（用户要求，2026-09-29）：便于**生产（本地环境）与测试环境隔离**——
     * 跑测试时可经 {@code FEED_BIGV_CONFIGFILE} 指向测试专用清单，无需改代码。
     * 覆盖链沿用本类两级（环境变量 → JVM 系统属性 → {@code app.properties}）。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 空串（不 fail-fast）；路径指向的文件不存在 / 不可读 / 内容非法 ⇒
     * 由 {@link FeedBigVConfig} **沿用上次快照（首次则回退本类静态值）+ 告警**，不得让应用起不来。
     */
    public static String getFeedBigVConfigFile() {
        return get("feed.bigv.configFile", "");
    }

    /**
     * 大V热更文件的**检查节流窗口**（毫秒，feed3-T27-A）：键 {@code feed.bigv.refreshMillis}（默认 5000）。
     *
     * <p><b>口径</b>：{@link FeedBigVConfig} 在**每次取值时**惰性检查是否已过本窗口——过了才重读文件并解析
     * （该类**不依赖 mtime**，靠"过窗口即全量重读"保证不漏检，见其类注释）。本值即名单 / 阈值改动的
     * **生效延迟上界**（不是固定后台轮询周期），故对外**不得承诺"改完立即生效"**。
     *
     * <p>{@code 0} = 每次取值都检查（测试用）；键缺失 / 空 → 默认 5000，不得让应用起不来。
     */
    public static long getFeedBigVRefreshMillis() {
        return getLong("feed.bigv.refreshMillis", 5000L);
    }

    // ===== feed 三期大V滞回判定（feed3-T28-A）=====

    /** 滞回降级系数键缺失 / 空时的兜底（与 {@code app.properties} 同值）。 */
    private static final double DEFAULT_FEED_BIGV_DOWNGRADE_RATIO = 0.8;

    /**
     * 大V滞回判定的**降级系数**（feed3-T28-A）：键 {@code feed.bigv.downgradeRatio}（默认 0.8）。
     *
     * <p><b>语义</b>：升级线 = 粉丝数 {@code >=} 阈值、降级线 = 粉丝数 {@code < 系数 × 阈值}；两线之间
     * （{@code [系数×阈值, 阈值)}）**状态不翻转**（滞回）。系数设 {@code 1.0} ⇒ 降级线等于升级线 ⇒
     * 滞回**等价关闭**（行为与 T28-A 之前一致，既有 {@code FEED_BIGV_THRESHOLD=1} 留证手法不受影响）。
     *
     * <p><b>为什么进快照</b>：系数是**判定输入**（写侧滞回维护据此决定升降级），故与阈值 / 名单**同批**
     * 进 {@link FeedBigVConfig} 的不可变快照；外部热更文件的同名键可覆盖本静态值。
     *
     * <p><b>合法区间 = {@code (0, 1]}，越界抛 {@link IllegalArgumentException}</b>（fail-fast，与
     * {@link #getFeedBigVUserIds()} 同口径）：{@code > 1} 会让降级线高于升级线（带内必翻转、滞回反向失效），
     * {@code <= 0} 会让降级永不发生（状态表只增不减）。键缺失 / 空 → 默认 0.8。
     */
    public static double getFeedBigVDowngradeRatio() {
        String raw = get("feed.bigv.downgradeRatio");
        double ratio = raw.isEmpty() ? DEFAULT_FEED_BIGV_DOWNGRADE_RATIO : Double.parseDouble(raw);
        return validateFeedBigVDowngradeRatio(ratio);
    }

    /**
     * 滞回降级系数的**唯一校验口径**（包可见：{@link FeedBigVConfig} 解析外部热更文件时复用，
     * 避免"静态键"与"文件键"两处规则漂移）：合法区间 = {@code (0, 1]}；越界 / 非有限数 ⇒ 抛。
     */
    static double validateFeedBigVDowngradeRatio(double ratio) {
        if (!(ratio > 0.0) || ratio > 1.0) {
            throw new IllegalArgumentException("feed.bigv.downgradeRatio 必须在 (0, 1] 区间: " + ratio);
        }
        return ratio;
    }

    // ===== feed 二期读侧两路归并（feed2-23 T23）=====

    /**
     * 读侧**总窗口上限** M（feed2-23 T23）：键 {@code feed.readWindowMax}（默认 300）。
     *
     * <p><b>三个"窗口"概念分立、不得互用</b>（NEEDS 4.0 对齐补录）：
     * <ol>
     *   <li>{@link #getFeedInboxWindowMax()} C=200 —— **表侧**：重建写入 {@code feed_inbox} 的每用户行数上限；</li>
     *   <li>feed 域信封 {@code FeedController.FEED_PAGE_SIZE_*} = 100 —— **接口侧**：一次 HTTP 返回条数；</li>
     *   <li>本方法 M=300 —— **读侧**：两路读（收件箱窗口 ∪ 大V发件箱窗口）归并去重降序后的**可见上限**，
     *       亦即**深翻边界**（越过窗口 → 空页）。</li>
     * </ol>
     * 关系：单响应信封 ≤ M（100 → 3 页）；**C 与 M 各自独立**——改 C 会改表侧重建量与重建成本，
     * 故二者不得复用同一键。
     *
     * <p>**带默认值（300）**：偏离散取值的容错口径，键缺失不得让应用起不来（同
     * {@link #getFeedInboxWindowMax()}；键存在但值非法照旧抛，fail-fast 语义不变）。
     * 读侧的表侧保留策略已随 **feed3-T29** 核算（= 复用重建顺带裁剪、本期不新增清理动作；残余含"读放大" = `U-36`）。
     */
    public static int getFeedReadWindowMax() {
        return getInt("feed.readWindowMax", 300);
    }

    /**
     * 大V发件箱「每作者最近条数」N（feed2-23 T23）：键 {@code feed.outbox.windowSize}（默认 20）。
     *
     * <p>大V内容**不落表**（反面对照：发件箱落表已判不做），读时按作者取最近 N 条 contentId
     * 合成"发件箱窗口"。N 独立于重建的 {@link #getFeedInboxWindowPerAuthor()} K——两者服务不同层
     * （K 决定表侧窗口取样，N 决定读时拉取深度），**不得复用同一键**。
     *
     * <p><b>带默认值（20）</b>：键缺失不得让应用起不来（同 {@link #getFeedInboxTtlSeconds()}）。
     * 默认值与 K 同量级（两者都是"窗口粒度"参数，取值口径一致）。
     */
    public static int getFeedOutboxWindowSize() {
        return getInt("feed.outbox.windowSize", 20);
    }

    /**
     * 大V发件箱读缓存 TTL（feed2-23 T23）：键 {@code feed.outbox.ttlMinutes}（默认 60 分钟）。
     *
     * <p>形态对齐 {@link #getFeedInboxTtlSeconds()}（分钟 → 秒）；TTL 只作**缓存淘汰**、无正确性含义
     * （正确性以 {@code content} 真相表为准；写后失效由 push 消费者负责）。
     *
     * <p>**带默认值（60）**：键缺失不得让应用起不来（同 {@link #getFeedInboxTtlSeconds()}）。
     */
    public static long getFeedOutboxTtlSeconds() {
        return getLong("feed.outbox.ttlMinutes", 60L) * 60;
    }

    // ===== feed 三期批量尺寸参数化（feed3-T29）=====
    //
    // 口径（NEEDS `N7` 之 feed 部分 / `NEXT_CYCLE_TASKS.md` T29）：把 feed 域两处**写死的批量尺寸**
    // 收进配置，与既有 `feed.*` 一致（**带默认值 + 环境变量 / -D 覆盖链**）。二者都是**执行参数**
    // （影响每批 DB / Redis 往返次数与 SQL 长度），**不是判定输入**，故不入 `FeedBigVConfig` 热更快照
    // （与 `feed.bigv.queryBatch` 同口径：改了要重启）。
    //
    // ⚠️ **必须在 app.properties 写键**：`AppConfig.load()` 只对**已存在的键**做 env / -D 覆盖
    // （遍历 `PROPS.stringPropertyNames()`）——键只写在 Java 默认值里则**取不到环境变量覆盖**。
    //
    // 边界（`AppConfig#getFeedBigVQueryBatch` 的类注释已声明）：`feed.bigv.queryBatch` 属 T26 内生需求、
    // **已参数化、不重复**；T29 的范围 = 下面这两个。

    /**
     * 写扩散**粉丝批量**（feed3-T29）：键 {@code feed.fanout.batch}（默认 200）。
     *
     * <p><b>用途</b>：{@link com.itheima.feed.service.FeedInboxWriter#fanout} 按本值**游标（keyset）分批**
     * 取"作者的全部粉丝"（feed3-T30 起：{@code user_id > cursor} 升序一批，走
     * {@code FollowDao#getFollowerUserIdsAfter}）——每轮 = 一批游标读 + 一条多行 {@code INSERT IGNORE}
     * + 一次单命令 {@code DEL}（三件套）。本值决定**每轮 DB / Redis 往返次数**与单条 INSERT 的占位符个数。
     * 缺省 200 与 {@link #getFeedInboxWindowMax()} C 同量级。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 默认 200，不得让应用起不来；键存在但非数字 → 照旧抛（fail-fast）。
     * **取值必须为正数**：非正会让游标取不到成员（{@code count <= 0} 直接返空）⇒ 写扩散**静默空转**
     * （内容不落任何粉丝收件箱），故在本方法内 fail-fast 拦下（先例 = {@link #validateFeedBigVDowngradeRatio}）。
     */
    public static int getFeedFanoutBatch() {
        return validatePositiveBatch("feed.fanout.batch", getInt("feed.fanout.batch", 200));
    }

    /**
     * 重建**窗口重查的作者批量**（feed3-T29）：键 {@code feed.rebuild.authorBatch}（默认 50）。
     *
     * <p><b>用途</b>：{@link com.itheima.feed.service.FeedRebuildService} 把"排除大V后的关注作者集"
     * 按本值切片，每片一条 {@code UNION ALL} 语句（一趟往返取各作者最近 K 条）——本值约束**单条 SQL 的
     * 长度与绑定参数个数**（占位符 ≈ 作者数 × K）。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 默认 50；键存在但非数字 → 照旧抛（fail-fast）。
     * **取值必须为正数**：切片循环用本值作步长（{@code from += batch}），非正会让重建**死循环**
     * → 故在本方法内 fail-fast（先例 = {@link #validateFeedBigVDowngradeRatio}）。
     */
    public static int getFeedRebuildAuthorBatch() {
        return validatePositiveBatch("feed.rebuild.authorBatch", getInt("feed.rebuild.authorBatch", 50));
    }

    /**
     * 批量尺寸的**唯一正数校验口径**（feed3-T29；形态对齐 {@link #validateFeedBigVDowngradeRatio}；
     * feed3-T31 起亦复用于投递队列容量——同为"非正即功能空转"的一类）。
     *
     * <p>为什么必须校验而非沿用"缺省容错、非法抛"的宽松口径：这些值都是**循环步长 / 窗口尺寸 / 容量**，
     * 非正数分别导致 fanout 静默空转、重建死循环、投递永远无法入队——属"配置错就出事且难发现"的一类，宁可启动即拒。
     */
    private static int validatePositiveBatch(String key, int batch) {
        if (batch <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数: " + batch);
        }
        return batch;
    }

    // ===== feed 三期异步投递（feed3-T31）=====

    /**
     * 投递线程池的**有界队列容量**（feed3-T31）：键 {@code feed.delivery.queueCapacity}（默认 1000）。
     *
     * <p><b>用途</b>：{@link com.itheima.mq.MqDeliveryDispatcher} 的 {@code ArrayBlockingQueue} 长度——
     * 发布 / 关注·取关的「publish + 确认等待」移入后台单 worker 后，队列是 Web 线程与 MQ 速度差之间的
     * 缓冲带。队列满 = MQ 慢（confirm 堆积）超出缓冲 ⇒ 新投递**丢弃 + 记 WARNING**（降级，不影响业务；
     * 丢失面 = NEEDS 4.3 残余②既有口径，由收件箱重建兜底）。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 默认 1000，不得让应用起不来；键存在但非数字 → 照旧抛（fail-fast）。
     * **取值必须为正数**：非正会让投递永远无法入队（等价投递功能整体失效），故在本方法内 fail-fast 拦下
     * （复用 {@link #validatePositiveBatch}，先例 = T29 批量尺寸）。
     */
    public static int getFeedDeliveryQueueCapacity() {
        return validatePositiveBatch("feed.delivery.queueCapacity", getInt("feed.delivery.queueCapacity", 1000));
    }

    /**
     * 投递线程池的**关停 drain 上界**（毫秒，feed3-T31）：键 {@code feed.delivery.drainTimeoutMillis}（默认 5000）。
     *
     * <p><b>用途</b>：应用关停时 {@link com.itheima.mq.MqDeliveryDispatcher#destroy()} 先停收新任务，
     * 再在本上界内等 worker 投完队列存量；超时 ⇒ {@code shutdownNow()} 丢弃剩余并记 WARNING。
     * "队列未投完即退出"的可接受性 = NEEDS 4.3 残余②（关停期队列内消息丢失与"投递失败只降级"同口径）；
     * 上界必须存在的原因：**不得挂住 Tomcat 关停**（T31 红线）。
     *
     * <p><b>容错口径</b>：键缺失 / 空 → 默认 5000；键存在但非数字 → 照旧抛（fail-fast）。
     * **取值必须为正数**：非正 = drain 无上界（挂住关停）或永不等待（存量必丢），故在本方法内 fail-fast。
     */
    public static long getFeedDeliveryDrainTimeoutMillis() {
        return validatePositiveMillis("feed.delivery.drainTimeoutMillis",
                getLong("feed.delivery.drainTimeoutMillis", 5000L));
    }

    /**
     * 毫秒级时长参数的**唯一正数校验口径**（feed3-T31；形态对齐 {@link #validatePositiveBatch}）：
     * 非正 = 关停等待无上界（挂住 Tomcat 关停）或永不等待，宁可启动即拒。
     */
    private static long validatePositiveMillis(String key, long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数: " + millis);
        }
        return millis;
    }

    // T5（cache-05）：索引懒重建失败冷却退避窗口（对齐熔断冷却先例 redis.breaker.cooldownMillis）

    public static long getContentIndexRebuildCooldownMillis() {
        return getLong("cache.content.indexRebuildCooldownMillis");
    }

    // ===== IoC =====

    // T17：@Inject 字段取不到 Bean 时是否 fail-fast（键缺失时 get() 返回空串 → 解析为 false；
    // 默认 true 由 app.properties 的 ioc.failFast=true 保证）
    public static boolean getIocFailFast() {
        return Boolean.parseBoolean(get("ioc.failFast"));
    }

    // ===== 日志（T1 log-01：输出端表 + 轮转参数）=====

    // 下列键在 app.properties 中均已给出，此处**仍带默认值**——与 db.*/redis.* 的 fail-fast 不同，
    // 日志配置缺失不得让应用起不来（对齐 LogUtil"某一路输出不可用即降级"的容错口径）。
    // 注意 getInt/get 语义：键**存在但值非法**照旧抛（fail-fast），只有"键缺失或为空"才取默认值。

    public static String getLogFile() {
        return get("log.file");
    }

    public static String getLogLevel() {
        return get("log.level");
    }

    /** 错误输出端文件名：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogErrorFile() {
        return get("log.error.file", "error.log");
    }

    public static String getLogErrorLevel() {
        return get("log.error.level", "SEVERE");
    }

    /** 访问输出端文件名：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogAccessFile() {
        return get("log.access.file", "access.log");
    }

    /** 审计输出端文件名（T8）：相对值只取文件名，目录与 {@code log.file} 相同（口径见 LogUtil 类注释）。 */
    public static String getLogAuditFile() {
        return get("log.audit.file", "audit.log");
    }

    /** 慢请求阈值（毫秒）：访问日志行 {@code cost >=} 此值 时带 {@code slow=1} 标记（D7 性能观测）。 */
    public static int getLogSlowRequestMs() {
        return getInt("log.slowRequestMs", 1000);
    }

    /** 单文件大小上限（字节）；{@code <=0} 视为不轮转。 */
    public static int getLogMaxBytes() {
        return getInt("log.maxBytes", 10 * 1024 * 1024);
    }

    /** 轮转保留的文件个数（含当前写入文件）。 */
    public static int getLogFileCount() {
        return getInt("log.fileCount", 5);
    }
}
