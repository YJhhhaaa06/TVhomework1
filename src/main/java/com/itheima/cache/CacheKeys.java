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

    /** 内容评论树：{@code content:comments:{id}}（JSON，Cache-Aside 数据 key）。 */
    public static String contentComments(long contentId) {
        return "content:comments:" + contentId;
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

    /** 内容点赞计数：{@code content:likeCount:{id}}（int，高频读，计数/成员分离 4.6）。 */
    public static String contentLikeCount(long contentId) {
        return "content:likeCount:" + contentId;
    }

    /** 内容点赞成员：{@code content:likeSet:{id}}（Set&lt;userId，低频成员查询）。 */
    public static String contentLikeSet(long contentId) {
        return "content:likeSet:" + contentId;
    }

    /** 评论点赞计数：{@code comment:likeCount:{id}}（int）。 */
    public static String commentLikeCount(long commentId) {
        return "comment:likeCount:" + commentId;
    }

    /** 评论点赞成员：{@code comment:likeSet:{id}}（Set&lt;userId）。 */
    public static String commentLikeSet(long commentId) {
        return "comment:likeSet:" + commentId;
    }

    /** 我关注了谁：{@code user:following:{userId}}（Set&lt;followedUserId，4.10）。 */
    public static String userFollowing(long userId) {
        return "user:following:" + userId;
    }

    /** 谁关注了我：{@code user:follower:{userId}}（Set&lt;userId，4.10）。 */
    public static String userFollower(long userId) {
        return "user:follower:" + userId;
    }

    /**
     * 数据 key → 统计域解析（T7 新增，key 生成与解析同源，唯一源收敛于本方法）。
     *
     * <p>注意前缀重叠：{@code content:} 是 {@code content:comments:} / {@code content:index:} /
     * {@code content:like*} 的公共前缀，**长前缀必须先于通用前缀判断**。
     * {@code empty:{dataKey}} 空标记先解包到内层数据 key 再归域。
     *
     * <p>映射（T7 执行定稿，见 {@link CacheDomain}）：
     * content:index 归 CONTENT（索引归内容域）；content:like* 与 comment:like* 归 LIKE；
     * content:comments 与 comment:* 归 COMMENT；content:{id} 归 CONTENT；
     * user:following 与 user:follower（user:* 兜底）归 FOLLOW；未知/null 归 OTHER。
     */
    public static CacheDomain domainOf(String dataKey) {
        if (dataKey == null) {
            return CacheDomain.OTHER;
        }
        if (dataKey.startsWith("empty:")) {
            return domainOf(dataKey.substring("empty:".length()));
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
        if (dataKey.startsWith("user:")) {
            return CacheDomain.FOLLOW;
        }
        return CacheDomain.OTHER;
    }
}