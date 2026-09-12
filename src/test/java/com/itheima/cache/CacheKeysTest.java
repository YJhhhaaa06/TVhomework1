package com.itheima.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheKeysTest {

    @Test
    void contentKeyUsesId() {
        assertEquals("content:1", CacheKeys.content(1L));
        assertEquals("content:42", CacheKeys.content(42L));
    }

    @Test
    void contentCommentsKeyUsesContentId() {
        assertEquals("content:comments:7", CacheKeys.contentComments(7L));
    }

    @Test
    void emptyKeyPrefixesDataKey() {
        assertEquals("empty:content:1", CacheKeys.empty("content:1"));
        assertEquals("empty:content:comments:7", CacheKeys.empty("content:comments:7"));
        assertEquals("empty:content:likeCount:1", CacheKeys.empty("content:likeCount:1"));
    }

    @Test
    void contentLikeKeysSeparateCountAndSet() {
        assertEquals("content:likeCount:1", CacheKeys.contentLikeCount(1L));
        assertEquals("content:likeSet:1", CacheKeys.contentLikeSet(1L));
    }

    @Test
    void commentLikeKeysSeparateCountAndSet() {
        assertEquals("comment:likeCount:9", CacheKeys.commentLikeCount(9L));
        assertEquals("comment:likeSet:9", CacheKeys.commentLikeSet(9L));
    }

    @Test
    void userFollowingKeyUsesUserId() {
        assertEquals("user:following:7", CacheKeys.userFollowing(7L));
    }

    @Test
    void userFollowerKeyUsesUserId() {
        assertEquals("user:follower:7", CacheKeys.userFollower(7L));
    }

    @Test
    void emptyMarkerConstantsFollowSpec() {
        assertEquals("1", CacheKeys.EMPTY_MARKER_VALUE);
        assertEquals(60L, CacheKeys.EMPTY_MARKER_TTL_SECONDS);
    }
}