package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JacksonCodecTest {

    private final JacksonCodec codec = new JacksonCodec();

    static class SampleDto {
        private long id;
        private String name;

        SampleDto() {
        }

        SampleDto(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Test
    void roundTripDto() {
        String json = codec.toJson(new SampleDto(1L, "alice"));
        SampleDto back = codec.fromJson(json, SampleDto.class);

        assertNotNull(back);
        assertEquals(1L, back.getId());
        assertEquals("alice", back.getName());
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(codec.toJson(null));
        assertNull(codec.fromJson(null, SampleDto.class));
        assertNull(codec.fromJson("", SampleDto.class));
    }

    @Test
    void typeReferenceForGenericList() {
        String json = codec.toJson(List.of(new SampleDto(1L, "a"), new SampleDto(2L, "b")));
        List<SampleDto> back = codec.fromJson(json,
                new TypeReference<List<SampleDto>>() {
                });

        assertEquals(2, back.size());
        assertEquals("a", back.get(0).getName());
        assertEquals(2L, back.get(1).getId());
    }

    @Test
    void invalidJsonThrowsCacheException() {
        assertThrows(CacheException.class, () -> codec.fromJson("{invalid-json", SampleDto.class));
        assertThrows(CacheException.class,
                () -> codec.fromJson("[\"1\"]", new TypeReference<List<SampleDto>>() {
                }));
    }

    // ==================== 第四期 T7（R-09）：关闭 FAIL_ON_UNKNOWN_PROPERTIES 后的兼容语义 ====================

    @Test
    void jsonWithUnknownFieldsIgnoresExtraProperties() {
        // 模拟 DTO 字段删/改名后旧缓存条目：含多余/旧字段的 JSON 仍可反序列化，多余字段忽略（R-09）
        String legacyJson = "{\"id\":1,\"name\":\"alice\",\"oldField\":\"obsolete\",\"renamedField\":42}";

        SampleDto back = codec.fromJson(legacyJson, SampleDto.class);

        assertNotNull(back);
        assertEquals(1L, back.getId());
        assertEquals("alice", back.getName());
    }

    @Test
    void dateSerializationStaysIsoNotTimestamp() {
        // 确认 SerializationFeature 现有配置不受影响：WRITE_DATES_AS_TIMESTAMPS 仍关闭（ISO-8601 而非时间戳）
        LocalDateTime value = LocalDateTime.of(2024, 1, 1, 10, 0);

        String json = codec.toJson(value);

        assertEquals("\"2024-01-01T10:00:00\"", json);
        assertEquals(value, codec.fromJson(json, LocalDateTime.class));
    }
}
