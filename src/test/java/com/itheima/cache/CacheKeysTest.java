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
    void t10aCommentTwoKeyGroupUsesSharedPrefixAndCommentDomain() {
        // T10-A：整树单 key 拆两键组 + count；共用 content:comments: 前缀 → domainOf 归 COMMENT 不变
        assertEquals("content:comments:7:roots", CacheKeys.contentCommentRoots(7L));
        assertEquals("content:comments:7:replies", CacheKeys.contentCommentReplies(7L));
        assertEquals("content:comments:7:count", CacheKeys.contentCommentRootCount(7L));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf(CacheKeys.contentCommentRoots(7L)));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf(CacheKeys.contentCommentReplies(7L)));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf(CacheKeys.contentCommentRootCount(7L)));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf("empty:" + CacheKeys.contentCommentRoots(7L)));
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
    void userFollowCountKeysUseUserIdAndMapToFollowDomain() {
        // T6（R-01）计数入缓存：独立计数 key 与成员 set 同构于 user: 前缀，domainOf 归 FOLLOW（无需扩展）
        assertEquals("user:followCount:7", CacheKeys.userFollowCount(7L));
        assertEquals("user:followerCount:7", CacheKeys.userFollowerCount(7L));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf(CacheKeys.userFollowCount(7L)));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf(CacheKeys.userFollowerCount(7L)));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf("empty:" + CacheKeys.userFollowCount(7L)));
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

    @Test
    void feedInboxKeyUsesUserIdAndMapsToFeedDomain() {
        // feed1-18（T18）：写扩散收件箱 key —— 生成与解析同源，归 FEED 域（含 empty/partial 解包）
        assertEquals("feed:inbox:7", CacheKeys.feedInbox(7L));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf(CacheKeys.feedInbox(7L)));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf("empty:" + CacheKeys.feedInbox(7L)));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf("partial:" + CacheKeys.feedInbox(7L)));
    }

    @Test
    void feedInboxPrefixConstantIsPublicForScanMatch() {
        assertEquals("feed:inbox:", CacheKeys.FEED_INBOX_PREFIX);
        assertTrue(CacheKeys.feedInbox(7L).startsWith(CacheKeys.FEED_INBOX_PREFIX));
    }

    @Test
    void feedInboxCacheKeysReturnsThreePieceSet() {
        // feed2-22（T22）：收件箱读缓存失效三件套（数据 key + empty: + partial:）——
        // 重建侧与 fanout 侧共用同一来源，防两处失效集漂移
        String[] keys = CacheKeys.feedInboxCacheKeys(7L);
        assertEquals(3, keys.length);
        assertEquals("feed:inbox:7", keys[0]);
        assertEquals("empty:feed:inbox:7", keys[1]);
        assertEquals("partial:feed:inbox:7", keys[2]);
        for (String key : keys) {
            assertEquals(CacheDomain.FEED, CacheKeys.domainOf(key), "三件套应全部归 FEED 域: " + key);
        }
    }

    @Test
    void feedOutboxKeyUsesAuthorIdAndMapsToFeedDomain() {
        // feed2-23（T23）：大V发件箱读缓存 key —— 生成与解析同源，归 FEED 域（含 empty 解包）
        assertEquals("feed:outbox:9", CacheKeys.feedOutbox(9L));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf(CacheKeys.feedOutbox(9L)));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf("empty:" + CacheKeys.feedOutbox(9L)));
        assertTrue(CacheKeys.feedOutbox(9L).startsWith(CacheKeys.FEED_OUTBOX_PREFIX));
    }

    @Test
    void feedOutboxCacheKeysReturnsTwoPieceSet() {
        // feed2-23（T23）：发件箱失效**两件套**（数据 key + empty:）——
        // outbox 恒为"该作者最近 N 条"这一有界窗口、无 partial 语义；empty: 用于吸收"无作品的大V"
        String[] keys = CacheKeys.feedOutboxCacheKeys(9L);
        assertEquals(2, keys.length);
        assertEquals("feed:outbox:9", keys[0]);
        assertEquals("empty:feed:outbox:9", keys[1]);
        for (String key : keys) {
            assertEquals(CacheDomain.FEED, CacheKeys.domainOf(key), "两件套应全部归 FEED 域: " + key);
        }
    }

    @Test
    void feedOutboxNamespaceIsDisjointFromInboxAndLock() {
        String outbox = CacheKeys.feedOutbox(9L);
        assertTrue(!outbox.startsWith(CacheKeys.FEED_INBOX_PREFIX), "发件箱不在收件箱命名空间内");
        assertTrue(!outbox.startsWith(CacheKeys.FEED_REBUILD_LOCK_PREFIX), "发件箱不在重建锁命名空间内");
        assertTrue(!CacheKeys.feedInbox(9L).startsWith(CacheKeys.FEED_OUTBOX_PREFIX), "反向也须不重叠");
    }

    @Test
    void feedRebuildLockKeyUsesUserIdAndMapsToFeedDomain() {
        // feed1-19（T19）：重建去重锁（SET NX EX + Lua CAS 释放）
        assertEquals("feed:rebuild:lock:7", CacheKeys.feedRebuildLock(7L));
        assertEquals(CacheDomain.FEED, CacheKeys.domainOf(CacheKeys.feedRebuildLock(7L)));
        assertTrue(CacheKeys.feedRebuildLock(7L).startsWith(CacheKeys.FEED_REBUILD_LOCK_PREFIX));
        assertTrue(!CacheKeys.feedRebuildLock(7L).startsWith(CacheKeys.FEED_INBOX_PREFIX),
                "锁不在收件箱命名空间内（不被 feed:inbox:* 遍历命中）");
    }

    @Test
    void feedPrefixDoesNotStealOtherDomains() {
        // 红线：新增 feed: 判定不得改变既有前缀的归域（长前缀优先顺序未动、OTHER 兜底仍在最后）
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf(CacheKeys.content(1L)));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf(CacheKeys.contentIndex(1, 2)));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf(CacheKeys.contentCommentRoots(7L)));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf(CacheKeys.contentLikeCount(1L)));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf(CacheKeys.userLikeSet(7L)));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf(CacheKeys.userFollower(7L)));
        assertEquals(CacheDomain.OTHER, CacheKeys.domainOf("unknown:key"));
        assertEquals(CacheDomain.OTHER, CacheKeys.domainOf(null));
    }
}