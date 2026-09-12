package com.itheima.cache;

/**
 * 三态读取结果载体：状态 + 可能的值。
 *
 * <p>约定：{@link CacheStatus#HIT_EMPTY} 时 value 恒为 null；
 * {@link CacheStatus#MISS} 时 value 为 null；{@link CacheStatus#HIT_DATA} 时 value 为反序列化结果。
 *
 * @param <T> 值类型
 */
public final class CacheResult<T> {

    private final CacheStatus status;
    private final T value;

    private CacheResult(CacheStatus status, T value) {
        this.status = status;
        this.value = value;
    }

    /** 未命中（可返回 MISS 表示无缓存，调用方按业务决定走 DB 或视同空）。 */
    public static <T> CacheResult<T> miss() {
        return new CacheResult<>(CacheStatus.MISS, null);
    }

    /** 空标记命中：已确认无数据。 */
    public static <T> CacheResult<T> hitEmpty() {
        return new CacheResult<>(CacheStatus.HIT_EMPTY, null);
    }

    /** 数据命中。 */
    public static <T> CacheResult<T> hitData(T value) {
        return new CacheResult<>(CacheStatus.HIT_DATA, value);
    }

    public CacheStatus status() {
        return status;
    }

    public T value() {
        return value;
    }

    /** 是否命中（HIT_EMPTY 或 HIT_DATA）。 */
    public boolean hit() {
        return status != CacheStatus.MISS;
    }
}