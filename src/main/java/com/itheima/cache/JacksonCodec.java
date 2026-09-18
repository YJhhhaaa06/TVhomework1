package com.itheima.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itheima.exception.CacheException;
import com.itheima.ioc.annotation.Component;

/**
 * 缓存 JSON 序列化工具（统一序列化规范，NEEDS 4.1）。
 *
 * <p>统一以 JSON 字符串存储缓存 value；复用 pom 已有 jackson-databind + jsr310。
 * 序列化/反序列化失败抛 {@link CacheException}，由 {@link CacheAside} 捕获后降级走 DB。
 *
 * <p>R-09（第四期 T7）：关闭 {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}——
 * DTO 删/改名后旧缓存条目含多余字段仍可反序列化（多余字段忽略），不再因脏 JSON 反复降级走 DB。
 * 仅影响反序列化未知字段；序列化输出与日期格式（WRITE_DATES_AS_TIMESTAMPS 仍关闭）零变化。
 */
@Component
public class JacksonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 对象转 JSON 字符串；null 输入返回 null。 */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CacheException("缓存序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }

    /** JSON 字符串转对象（指定类型）；null/空白输入返回 null。 */
    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new CacheException("缓存反序列化失败: " + type.getSimpleName(), e);
        }
    }

    /** JSON 字符串转对象（泛型容器，如 List&lt;T&gt;）。 */
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new CacheException("缓存反序列化失败: " + typeRef.getType().getTypeName(), e);
        }
    }
}