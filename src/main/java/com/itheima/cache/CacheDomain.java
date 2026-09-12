package com.itheima.cache;

/**
 * 缓存统计分域（T7 观测埋点，NEEDS 4.14）。
 *
 * <p>按数据 key 前缀将缓存访问归入各域，供分域命中率/穿透/降级/写失败计数与
 * 惰性日志摘要使用（O-8 TTL 精调需要各域自己的观测曲线）。
 * 域解析唯一收敛于 {@link CacheKeys#domainOf(String)}（key 生成与解析同源，不漂移）。
 *
 * <p>映射约定（T7 执行定稿）：
 * <ul>
 *   <li>{@link #CONTENT}：内容详情 {@code content:{id}}、类型分区索引 {@code content:index:*}；</li>
 *   <li>{@link #COMMENT}：评论树 {@code content:comments:{id}}（comment:* 通用前缀防御兜底）；</li>
 *   <li>{@link #LIKE}：点赞计数/成员 {@code content:likeCount}/{@code content:likeSet}/{@code comment:like...}；</li>
 *   <li>{@link #FOLLOW}：关注关系 {@code user:following}/{@code user:follower}；</li>
 *   <li>{@link #OTHER}：未知前缀 / null 兜底。</li>
 * </ul>
 * {@code empty:{dataKey}} 空标记先解包到内层数据 key 再归域。
 */
public enum CacheDomain {
    CONTENT,
    COMMENT,
    LIKE,
    FOLLOW,
    OTHER
}