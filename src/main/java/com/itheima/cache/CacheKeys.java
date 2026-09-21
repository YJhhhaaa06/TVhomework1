package com.itheima.cache;

/**
 * 统一缓存 key 命名/生成规范（T1 定稿，本周期唯一源）。
 *
 * <p>业务缓存类（内容/评论/点赞/关注）一律引用本类生成 key，不得另造格式。
 * 规范与 CURRENT_ARCHITECTURE.md 六.Redis 设计 6.2 保持一致。
 *
 * <p>空标记（NEEDS 4.4）：不碰业务容器，另起 {@code empty:{dataKey}} 独立 String key
 * 标记"已确认无数据"，短 TTL 由 {@link #EMPTY_MARKER_TTL_SECONDS} 控制。
 */
public final class CacheKeys {

    /** 空标记的固定值（仅需 EXISTS 判断，值语义不重要）。 */
    public static final String EMPTY_MARKER_VALUE = "1";

    /** 部分装载标记的固定值（T11-C，同 {@link #EMPTY_MARKER_VALUE} 仅需 EXISTS 判断）。 */
    public static final String PARTIAL_MARKER_VALUE = "1";

    /** 空标记短 TTL（秒）：NEEDS 4.4 约定约 30s~5min，取 60s。 */
    public static final long EMPTY_MARKER_TTL_SECONDS = 60;

    /** 内容索引 key 前缀：{@code content:index:}（生成、{@link #domainOf} 解析、SCAN 匹配同源，防漂移）。 */
    public static final String CONTENT_INDEX_PREFIX = "content:index:";

    private CacheKeys() {
    }

    /** 内容详情：{@code content:{id}}（JSON，Cache-Aside 数据 key）。 */
    public static String content(long contentId) {
        return "content:" + contentId;
    }

    /**
     * 内容评论树（旧整树单 key）：{@code content:comments:{id}}（JSON，Cache-Aside 数据 key）。
     *
     * <p>T10-A 起停用（评论缓存改两键组：{@link #contentCommentRoots}/{@link #contentCommentReplies}/
     * {@link #contentCommentRootCount}），本方法保留仅供兼容/清理引用；旧 key 由 TTL 自然回收。
     */
    public static String contentComments(long contentId) {
        return "content:comments:" + contentId;
    }

    /** 评论主楼序列（T10-A 两键组①）：{@code content:comments:{id}:roots}（LIST，窗口读 + 尾追加）。 */
    public static String contentCommentRoots(long contentId) {
        return contentComments(contentId) + ":roots";
    }

    /** 评论楼中楼（T10-A 两键组②）：{@code content:comments:{id}:replies}（HASH，field=主楼 id）。 */
    public static String contentCommentReplies(long contentId) {
        return contentComments(contentId) + ":replies";
    }

    /** 评论主楼总数（T10-A 真实 total）：{@code content:comments:{id}:count}（String int，窗口装载首装时惰性 COUNT + 增删同步维护）。 */
    public static String contentCommentRootCount(long contentId) {
        return contentComments(contentId) + ":count";
    }

    /**
     * 内容类型分区索引：{@code content:index:{type}:{categoryId}}（LIST，新前序推荐索引）。
     *
     * <p>type=-1 / categoryId=-1 表示通配维度（推荐接口 type/category 为空时的 -1 归一）。
     * 三期 T6 归一：原 {@code ContentCache.indexKey} 私有拼接并入本方法，生成与
     * {@link #domainOf} 解析、SCAN 匹配（{@link #CONTENT_INDEX_PREFIX}）同源。
     */
    public static String contentIndex(int type, int categoryId) {
        return CONTENT_INDEX_PREFIX + type + ":" + categoryId;
    }

    /**
     * 空标记：{@code empty:{dataKey}}（String），与数据 key 一一对应，短 TTL。
     *
     * @param dataKey 数据 key（如 {@link #content(long)}）
     */
    public static String empty(String dataKey) {
        return "empty:" + dataKey;
    }

    /**
     * 部分装载标记（T11-C）：{@code partial:{dataKey}}（String），与数据 key 一一对应。
     *
     * <p>语义（与 {@link #empty(String)} 互斥）：**存在 ⇒ 集合不完整**——已知内容为 DB 按序的
     * **前 W 个**（W = 数据 key 的 ZCARD）；**不存在 ⇒ 集合完整**。这把改造前"数据 key 存在
     * 即完整"的不变量从**存在性**放宽为**标记**（前缀窗口装载的落点，治 U-18）。
     *
     * <p>TTL 与数据 key **同步续期**（读命中时一并 EXPIRE，见 ZSetCache）：一旦两者生命周期
     * 错位（标记先过期），已装载的前缀会被误判为完整集合 → 静默漏成员，属数据正确性问题。
     */
    public static String partial(String dataKey) {
        return "partial:" + dataKey;
    }

    /** 内容点赞计数：{@code content:likeCount:{id}}（int，高频读，计数/成员分离 4.6）。 */
    public static String contentLikeCount(long contentId) {
        return "content:likeCount:" + contentId;
    }

    /** 评论点赞计数：{@code comment:likeCount:{id}}（int）。 */
    public static String commentLikeCount(long commentId) {
        return "comment:likeCount:" + commentId;
    }

    /**
     * 我点赞过的内容：{@code user:likeSet:{userId}}（Set&lt;contentId，第四期 T4 装载反转：
     * 成员 key 由内容维度反转为用户维度，与 {@link #userFollowing(long)} 同构——装载量 = 该用户
     * 点赞的内容数，与内容热度解耦（R-08）；旧 {@code content:likeSet:{contentId}} 不双写，TTL 自然回收）。
     */
    public static String userLikeSet(long userId) {
        return "user:likeSet:" + userId;
    }

    /**
     * 我点赞过的评论：{@code user:commentLikeSet:{userId}}（Set&lt;commentId，第四期 T4 反转，
     * 逻辑同 {@link #userLikeSet(long)}；旧 {@code comment:likeSet:{commentId}} 不双写，TTL 自然回收）。
     */
    public static String userCommentLikeSet(long userId) {
        return "user:commentLikeSet:" + userId;
    }

    /** 我关注了谁：{@code user:following:{userId}}（Set&lt;followedUserId，4.10）。 */
    public static String userFollowing(long userId) {
        return "user:following:" + userId;
    }

    /** 谁关注了我：{@code user:follower:{userId}}（Set&lt;userId，4.10）。 */
    public static String userFollower(long userId) {
        return "user:follower:" + userId;
    }

    /** 我的关注数：{@code user:followCount:{userId}}（String int，第四期 T6 计数入缓存 R-01，与 {@link #contentLikeCount(long)} 同构）。 */
    public static String userFollowCount(long userId) {
        return "user:followCount:" + userId;
    }

    /** 我的粉丝数：{@code user:followerCount:{userId}}（String int，第四期 T6 计数入缓存 R-01）。 */
    public static String userFollowerCount(long userId) {
        return "user:followerCount:" + userId;
    }

    /**
     * 数据 key → 统计域解析（T7 新增，key 生成与解析同源，唯一源收敛于本方法）。
     *
     * <p>注意前缀重叠：{@code content:} 是 {@code content:comments:} / {@code content:index:} /
     * {@code content:like*} 的公共前缀，**长前缀必须先于通用前缀判断**。
     * {@code empty:{dataKey}} 空标记与 {@code partial:{dataKey}} 部分装载标记（T11-C）
     * 先解包到内层数据 key 再归域。
     *
     * <p>映射（T7 执行定稿 + 第四期 T4 扩展，见 {@link CacheDomain}）：
     * content:index 归 CONTENT（索引归内容域）；content:like* 与 comment:like* 归 LIKE；
     * content:comments 与 comment:* 归 COMMENT；content:{id} 归 CONTENT；
     * user:following 与 user:follower（user:* 兜底）归 FOLLOW；**user:like* 与 user:commentLike*
     * （T4 装载反转后的用户维度点赞成员）在 user:* 兜底之前归 LIKE**；未知/null 归 OTHER。
     */
    public static CacheDomain domainOf(String dataKey) {
        if (dataKey == null) {
            return CacheDomain.OTHER;
        }
        if (dataKey.startsWith("empty:")) {
            return domainOf(dataKey.substring("empty:".length()));
        }
        if (dataKey.startsWith("partial:")) {
            return domainOf(dataKey.substring("partial:".length()));
        }
        if (dataKey.startsWith(CONTENT_INDEX_PREFIX)) {
            return CacheDomain.CONTENT;
        }
        if (dataKey.startsWith("content:like")) {
            return CacheDomain.LIKE;
        }
        if (dataKey.startsWith("content:comments:")) {
            return CacheDomain.COMMENT;
        }
        if (dataKey.startsWith("comment:like")) {
            return CacheDomain.LIKE;
        }
        if (dataKey.startsWith("comment:")) {
            return CacheDomain.COMMENT;
        }
        if (dataKey.startsWith("content:")) {
            return CacheDomain.CONTENT;
        }
        // 长前缀优先：user:commentLikeSet 不以 user:like 开头，两条并列，先于通用 user:* 兜底
        if (dataKey.startsWith("user:commentLike")) {
            return CacheDomain.LIKE;
        }
        if (dataKey.startsWith("user:like")) {
            return CacheDomain.LIKE;
        }
        if (dataKey.startsWith("user:")) {
            return CacheDomain.FOLLOW;
        }
        return CacheDomain.OTHER;
    }
}