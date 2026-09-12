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
}