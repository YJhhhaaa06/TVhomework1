package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.exception.ServerException;
import com.itheima.follow.service.FollowCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedReadService} 单测（feed2-23 T23）：读态闸门 / 两路读编排与顺序 / 归并去重降序截断 M /
 * 每次请求重算整窗（无整窗缓存）/ 失败面（收件箱腿 DB 失败上抛、大V腿失败不影响）。
 *
 * <p>隔离手法：mock 两条腿（{@link FeedInboxReader} / {@link FeedOutboxReader}）与
 * {@link FollowCache} / {@link FeedBigVRouter}，**不依赖真实 Redis / DB**；交互序列记入 {@code events}。
 */
class FeedReadServiceTest {

    private static final long USER = 7L;

    private FollowCache followCache;
    private FeedBigVRouter bigVRouter;
    private FeedInboxReader inboxReader;
    private FeedOutboxReader outboxReader;
    private FeedReadService service;

    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        followCache = mock(FollowCache.class);
        bigVRouter = mock(FeedBigVRouter.class);
        inboxReader = mock(FeedInboxReader.class);
        outboxReader = mock(FeedOutboxReader.class);
        service = new FeedReadService(followCache, bigVRouter, inboxReader, outboxReader);
        events.clear();
    }

    // ==================== 读态闸门 ====================

    @Test
    void unsyncedReturnsFallbackSignalWithoutTouchingAnyLeg() {
        when(inboxReader.isSynced(USER)).thenReturn(false);

        FeedReadService.FeedReadResult result = service.readWindow(USER);

        assertFalse(result.synced(), "未同步 ⇒ 调用方回退纯拉");
        assertTrue(result.windowIds().isEmpty());
        verify(followCache, never()).getFollowingIds(anyLong());
        verify(inboxReader, never()).readInbox(anyLong());
        verify(bigVRouter, never()).isBigVBatch(any());
        verify(outboxReader, never()).readOutbox(any());
    }

    @Test
    void syncedEmptyFollowingReturnsEmptyWindowWithoutDbOrOutbox() {
        when(inboxReader.isSynced(USER)).thenReturn(true);
        when(followCache.getFollowingIds(USER)).thenReturn(Collections.emptyList());

        FeedReadService.FeedReadResult result = service.readWindow(USER);

        assertTrue(result.synced());
        assertTrue(result.windowIds().isEmpty());
        verify(inboxReader, never()).readInbox(anyLong());
        verify(outboxReader, never()).readOutbox(any());
    }

    // ==================== 归并 ====================

    @Test
    void mergesInboxAndOutboxDedupDescending() {
        stubSynced(List.of(9L));
        when(inboxReader.readInbox(USER)).thenReturn(List.of(5L, 3L, 1L));
        when(bigVRouter.isBigVBatch(List.of(9L))).thenReturn(Set.of(9L));
        when(outboxReader.readOutbox(Set.of(9L))).thenReturn(List.of(5L, 4L, 2L));

        FeedReadService.FeedReadResult result = service.readWindow(USER);

        // 两路并集去重：{1,2,3,4,5} → contentId 降序
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), result.windowIds());
    }

    @Test
    void truncatesMergedWindowToReadWindowMax() {
        int max = AppConfig.getFeedReadWindowMax();
        List<Long> inbox = ids(1, max);                  // 1..M
        List<Long> outbox = ids(max + 1, max + 100);     // M+1..M+100（两路合计 > M）
        stubSynced(List.of(9L));
        when(inboxReader.readInbox(USER)).thenReturn(inbox);
        when(bigVRouter.isBigVBatch(List.of(9L))).thenReturn(Set.of(9L));
        when(outboxReader.readOutbox(Set.of(9L))).thenReturn(outbox);

        FeedReadService.FeedReadResult result = service.readWindow(USER);

        assertEquals(max, result.windowIds().size(), "合并后必须截断到读侧总窗口 M");
        assertEquals((long) (max + 100), result.windowIds().get(0), "保留的是最大的 M 个（降序）");
        assertEquals((long) (max + 100 - max + 1),
                result.windowIds().get(result.windowIds().size() - 1));
    }

    @Test
    void skipsOutboxLegWhenNoBigVAuthor() {
        stubSynced(List.of(8L, 9L));
        when(inboxReader.readInbox(USER)).thenReturn(List.of(2L, 1L));
        when(bigVRouter.isBigVBatch(List.of(8L, 9L))).thenReturn(Collections.emptySet());

        assertEquals(List.of(2L, 1L), service.readWindow(USER).windowIds());
        verify(outboxReader, never()).readOutbox(any());
    }

    @Test
    void readsLegsInFixedOrderWithSingleBigVQuery() {
        stubSynced(List.of(9L));
        when(inboxReader.readInbox(USER)).thenAnswer(inv -> {
            events.add("INBOX");
            return List.of(2L);
        });
        when(bigVRouter.isBigVBatch(List.of(9L))).thenAnswer(inv -> {
            events.add("BIGV");
            return Set.of(9L);
        });
        when(outboxReader.readOutbox(Set.of(9L))).thenAnswer(inv -> {
            events.add("OUTBOX");
            return List.of(3L);
        });

        service.readWindow(USER);

        // 顺序：闸门 → 收件箱腿 → 大V判定（**单次**）→ 发件箱腿
        assertEquals(List.of("INBOX", "BIGV", "OUTBOX"), events);
        verify(bigVRouter, times(1)).isBigVBatch(any());
    }

    @Test
    void recomputesWholeWindowOnEveryCall() {
        // 不引入"整窗缓存"：每次 /feed 都重算（页级切片由调用方在结果上做）
        stubSynced(List.of(9L));
        when(inboxReader.readInbox(USER)).thenReturn(List.of(1L));
        when(bigVRouter.isBigVBatch(List.of(9L))).thenReturn(Set.of(9L));
        when(outboxReader.readOutbox(Set.of(9L))).thenReturn(List.of(2L));

        service.readWindow(USER);
        service.readWindow(USER);

        verify(inboxReader, times(2)).readInbox(USER);
        verify(bigVRouter, times(2)).isBigVBatch(any());
        verify(outboxReader, times(2)).readOutbox(any());
    }

    // ==================== 失败面 ====================

    @Test
    void propagatesInboxLegFailure() {
        // 收件箱腿 DB 失败 = 读路径 DB 失败 ⇒ 上抛（500），与切换前的纯拉一致：不留半条时间线
        stubSynced(List.of(9L));
        when(inboxReader.readInbox(USER)).thenThrow(new ServerException("收件箱窗口装载失败"));

        assertThrows(ServerException.class, () -> service.readWindow(USER));
    }

    // ==================== 辅助 ====================

    private void stubSynced(List<Long> following) {
        when(inboxReader.isSynced(USER)).thenReturn(true);
        when(followCache.getFollowingIds(USER)).thenReturn(following);
    }

    private static List<Long> ids(int fromInclusive, int toInclusive) {
        List<Long> list = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            list.add((long) i);
        }
        return list;
    }
}
