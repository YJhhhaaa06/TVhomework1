package com.itheima.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void contentLikeCountKeyUsesContentId() {
        assertEquals("content:likeCount:1", CacheKeys.contentLikeCount(1L));
    }

    @Test
    void commentLikeCountKeyUsesCommentId() {
        assertEquals("comment:likeCount:9", CacheKeys.commentLikeCount(9L));
    }

    @Test
    void userLikeSetsFollowUserShape() {
        // T4 装载反转：内容/评论点赞成员 key 由内容/评论维度转为用户维度（与 user:following 同构）
        assertEquals("user:likeSet:7", CacheKeys.userLikeSet(7L));
        assertEquals("user:commentLikeSet:7", CacheKeys.userCommentLikeSet(7L));
    }

    @Test
    void userLikeSetKeysMapToLikeDomain() {
        // T4 domainOf 扩展：user:like*/user:commentLike* 在 user:* 兜底之前归 LIKE；
        // user:following/follower 仍归 FOLLOW（长前缀 user:like 不误伤）
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf(CacheKeys.userLikeSet(7L)));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf(CacheKeys.userCommentLikeSet(7L)));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("empty:" + CacheKeys.userLikeSet(7L)));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf(CacheKeys.userFollowing(7L)));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf(CacheKeys.userFollower(7L)));
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
    void contentIndexKeyUsesTypeAndCategory() {
        assertEquals("content:index:1:2", CacheKeys.contentIndex(1, 2));
        assertEquals("content:index:2:-1", CacheKeys.contentIndex(2, -1));
        assertEquals("content:index:-1:1", CacheKeys.contentIndex(-1, 1));
        assertEquals("content:index:-1:-1", CacheKeys.contentIndex(-1, -1));
    }

    @Test
    void contentIndexKeyGenerationAndParsingSameSource() {
        // 三期 T6 U-08：生成（contentIndex）与解析（domainOf）同源，CacheKeys 为唯一源
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf(CacheKeys.contentIndex(1, 2)));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf(CacheKeys.contentIndex(-1, -1)));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("empty:" + CacheKeys.contentIndex(1, 2)));
    }

    @Test
    void contentIndexPrefixConstantIsPublicForScanMatch() {
        assertEquals("content:index:", CacheKeys.CONTENT_INDEX_PREFIX);
        assertTrue(CacheKeys.contentIndex(1, 2).startsWith(CacheKeys.CONTENT_INDEX_PREFIX));
    }

    @Test
    void emptyMarkerConstantsFollowSpec() {
        assertEquals("1", CacheKeys.EMPTY_MARKER_VALUE);
        assertEquals(60L, CacheKeys.EMPTY_MARKER_TTL_SECONDS);
    }
}