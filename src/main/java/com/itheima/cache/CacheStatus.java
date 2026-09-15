package com.itheima.cache;

/**
 * 缓存三态（NEEDS 4.3 Cache-Aside 三态区分）：
 *
 * <ul>
 *   <li>{@link #MISS}：key 不存在 —— 未知/未加载/已过期，需查 DB 回填；</li>
 *   <li>{@link #HIT_EMPTY}：空标记命中 —— 已加载、确认无数据，直接返回空、不查 DB；</li>
 *   <li>{@link #HIT_DATA}：数据命中 —— 有数据，直接返回。</li>
 * </ul>
 */
public enum CacheStatus {
    MISS,
    HIT_EMPTY,
    HIT_DATA
}