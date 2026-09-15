package com.itheima.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.exception.CacheException;
import org.junit.jupiter.api.Test;

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
}