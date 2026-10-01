package com.itheima.follow.service;

import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InboxRebuildDebouncer} 单测（feed3-T33-A）：per-user 尾沿合并 / 不同用户独立 / 窗口外不合并 /
 * 0 = 显式关闭去抖 / 关停 flush / 合并结论行 / 负数 fail-fast。
 *
 * <p>手法：**受控窗口**（毫秒级构造，不读 AppConfig），无 broker / 无 DB；等待一律"轮询 + 上界"，
 * 不给固定 sleep 兜底；{@code tearDown} 关停清场（幂等）。
 */
class InboxRebuildDebouncerTest {

    private static final long USER_A = 7L;
    private static final long USER_B = 8L;

    /** 受控窗口：足够短以让用例快，足够长以让"N 次登记"落在同一窗口内。 */
    private static final long WINDOW_MILLIS = 120L;

    private InboxRebuildDebouncer debouncer;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        debouncer = new InboxRebuildDebouncer(WINDOW_MILLIS);
        probe = LogProbe.attachTo(LogUtil.getLogger(InboxRebuildDebouncer.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
        debouncer.destroy();
    }

    @Test
    void mergesBurstOfSameUserIntoSingleExecution() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Runnable task = runs::incrementAndGet;

        debouncer.schedule(USER_A, task);
        debouncer.schedule(USER_A, task);
        debouncer.schedule(USER_A, task);

        assertTrue(awaitAtLeast(runs, 1), "窗口到期后应执行一次");
        Thread.sleep(WINDOW_MILLIS * 2);   // 再等两个窗口：确认"只保留最后一次"，不会补执行前两次
        assertEquals(1, runs.get(), "同一用户窗口内 3 次登记只应执行 1 次（尾沿合并）");
    }

    @Test
    void separateWindowsExecuteTwice() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        debouncer.schedule(USER_A, runs::incrementAndGet);
        assertTrue(awaitAtLeast(runs, 1), "第一个窗口应触发");
        debouncer.schedule(USER_A, runs::incrementAndGet);
        assertTrue(awaitAtLeast(runs, 2), "第二个窗口应再次触发（窗口外不合并）");

        assertEquals(2, runs.get());
    }

    @Test
    void distinctUsersAreMergedIndependently() throws Exception {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();

        debouncer.schedule(USER_A, a::incrementAndGet);
        debouncer.schedule(USER_B, b::incrementAndGet);
        debouncer.schedule(USER_A, a::incrementAndGet);

        assertTrue(awaitAtLeast(a, 1));
        assertTrue(awaitAtLeast(b, 1));
        assertEquals(1, a.get(), "同一用户合并为一次");
        assertEquals(1, b.get(), "不同用户各自计时、互不合并");
    }

    @Test
    void zeroWindowDisablesDebounceAndRunsImmediately() {
        InboxRebuildDebouncer immediate = new InboxRebuildDebouncer(0L);
        try {
            AtomicInteger runs = new AtomicInteger();
            immediate.schedule(USER_A, runs::incrementAndGet);
            immediate.schedule(USER_A, runs::incrementAndGet);
            assertEquals(2, runs.get(), "0 = 显式关闭去抖 ⇒ 每次登记都立即执行");
        } finally {
            immediate.destroy();
        }
    }

    @Test
    void destroyFlushesPendingRequestsAndRunsLaterRequestsImmediately() {
        // 长窗口 ⇒ 保证登记后仍处于"待触发"状态，flush 行为可判
        InboxRebuildDebouncer slow = new InboxRebuildDebouncer(60_000L);
        AtomicInteger runs = new AtomicInteger();

        slow.schedule(USER_A, runs::incrementAndGet);
        slow.schedule(USER_B, runs::incrementAndGet);
        assertEquals(0, runs.get(), "长时间窗口内不应执行");

        slow.destroy();
        assertEquals(2, runs.get(), "关停 flush ⇒ 待触发项立即执行（不丢弃）");

        slow.schedule(USER_A, runs::incrementAndGet);
        assertEquals(3, runs.get(), "关停后再登记：直接执行、且绝不外抛");
    }

    @Test
    void mergedBurstLogsExactlyOneInfoConclusionLine() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        debouncer.schedule(USER_A, runs::incrementAndGet);
        debouncer.schedule(USER_A, runs::incrementAndGet);

        assertTrue(awaitAtLeast(runs, 1));
        List<LogRecord> infos = probe.atLevel(Level.INFO);
        assertEquals(1, infos.size(), () -> "应恰一条合并结论行，实际: " + probe.records());
        assertTrue(infos.getFirst().getMessage().contains("已去抖合并"),
                () -> "合并结论行文案: " + infos.getFirst().getMessage());
    }

    @Test
    void singleRequestLogsNothing() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        debouncer.schedule(USER_A, runs::incrementAndGet);
        assertTrue(awaitAtLeast(runs, 1));

        assertTrue(probe.records().isEmpty(),
                () -> "未发生合并 ⇒ 零日志（保持\"成功路径不刷日志\"口径），实际: " + probe.records());
    }

    @Test
    void negativeWindowRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new InboxRebuildDebouncer(-1L));
    }

    /** 轮询等待计数达到目标（上界 2s）——替代固定 sleep，避免慢机器上假失败。 */
    private static boolean awaitAtLeast(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (counter.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        return counter.get() >= target;
    }
}
