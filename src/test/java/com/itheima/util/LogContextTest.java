package com.itheima.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2（log-02）请求关联载体单测：生成口径（唯一 + 固定长度 + 可读）、set/get/clear 往返与入参归一、
 * 跨线程不串号、捕获-恢复（{@link LogContext#wrap(Runnable)}）在异步线程生效且**不引入异步执行**、
 * 与 {@link RequestContext} 互不干扰（D6）。
 *
 * <p>纯组件测试，无外部依赖；每个用例后被清理，避免 ThreadLocal 残留污染同 JVM 内的其它测试类。
 */
class LogContextTest {

    @AfterEach
    void clearAfterEach() {
        LogContext.clear();
    }

    // ==================== 生成口径：唯一 + 固定长度 ====================

    @Test
    void generatedRequestIdHasFixedLengthAndReadableShape() {
        for (int i = 0; i < 200; i++) {
            String id = LogContext.newRequestId();

            assertEquals(LogContext.REQUEST_ID_LENGTH, id.length(), "reqId 长度应固定（长度可控）: " + id);
            assertTrue(id.matches("[0-9a-f]+"), "reqId 应为小写十六进制（可读 / 可 grep / 可按时间粗排）: " + id);
        }
    }

    @Test
    void generatedRequestIdsAreUniqueSequentially() {
        int count = 5_000;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            assertTrue(ids.add(LogContext.newRequestId()), "连续生成不得重复（第 " + i + " 次）");
        }

        assertEquals(count, ids.size());
    }

    @Test
    void generatedRequestIdsAreUniqueAcrossThreads() throws Exception {
        int threads = 8;
        int perThread = 500;
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < perThread; n++) {
                    ids.add(LogContext.newRequestId());
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * perThread, ids.size(),
                "并发生成不得重复（同一毫秒内靠原子自增序号区分）");
    }

    // ==================== set / get / clear 往返与入参归一 ====================

    @Test
    void setGetClearRoundTrip() {
        assertNull(LogContext.getRequestId(), "非请求线程无 reqId（本类无默认值兜底）");

        LogContext.setRequestId("req-abc");
        assertEquals("req-abc", LogContext.getRequestId());

        LogContext.clear();
        assertNull(LogContext.getRequestId(), "clear 后必须读到 null（filter 的 finally 清理口径）");
    }

    @Test
    void nullOrBlankValueIsNormalizedToClear() {
        LogContext.setRequestId("req-abc");
        LogContext.setRequestId(null);
        assertNull(LogContext.getRequestId(), "null 视为清除（不留空值字段）");

        LogContext.setRequestId("req-abc");
        LogContext.setRequestId("   ");
        assertNull(LogContext.getRequestId(), "纯空白视为清除（不留空值字段）");
    }

    @Test
    void valueIsTrimmedSoLogLineStaysFieldSplittable() {
        LogContext.setRequestId("  req-abc  ");

        assertEquals("req-abc", LogContext.getRequestId(), "两侧空白应去除（否则日志行的空白分隔会把字段切碎）");
    }

    // ==================== 跨线程隔离（ThreadLocal 非 Inheritable） ====================

    @Test
    void requestIdDoesNotLeakAcrossThreads() throws Exception {
        LogContext.setRequestId("req-main");
        String[] seen = new String[1];

        Thread other = new Thread(() -> seen[0] = LogContext.getRequestId());
        other.start();
        other.join();

        assertNull(seen[0], "新线程不得继承调用线程的 reqId（也不得用 InheritableThreadLocal：池化线程会串号）");
        assertEquals("req-main", LogContext.getRequestId(), "其它线程的读写不得影响本线程");
    }

    // ==================== 捕获-恢复传递机制（D8） ====================

    @Test
    void captureAndRestoreBringValueToAnotherThread() throws Exception {
        LogContext.setRequestId("req-main");
        LogContext.Snapshot snapshot = LogContext.capture();
        LogContext.clear();

        String[] seen = new String[1];
        Thread worker = new Thread(() -> {
            LogContext.restore(snapshot);
            seen[0] = LogContext.getRequestId();
        });
        worker.start();
        worker.join();

        assertEquals("req-main", seen[0], "恢复快照后异步线程应读到调用线程的 reqId");
        assertEquals(new LogContext.Snapshot("req-main"), snapshot, "快照应如实携带捕获时刻的值");
    }

    @Test
    void restoringSnapshotWithoutRequestIdClearsCurrentThread() {
        LogContext.setRequestId("req-stale");

        LogContext.restore(new LogContext.Snapshot(null));
        assertNull(LogContext.getRequestId(), "空快照恢复 = 清空：防池化线程残留上一任务的 reqId");

        LogContext.setRequestId("req-stale");
        LogContext.restore(null);
        assertNull(LogContext.getRequestId(), "null 快照同样按清空处理");
    }

    @Test
    void wrappedTaskSeesCallerRequestIdOnAnotherThread() throws Exception {
        LogContext.setRequestId("req-main");
        String[] seen = new String[1];
        Runnable wrapped = LogContext.wrap(() -> seen[0] = LogContext.getRequestId());
        LogContext.clear(); // 提交后即清掉调用线程的值：任务体读到的必须是**捕获时刻**的快照

        Thread worker = new Thread(wrapped);
        worker.start();
        worker.join();

        assertEquals("req-main", seen[0], "异步任务应带着提交线程的 reqId（捕获-恢复生效）");
    }

    @Test
    void wrappedTaskLeavesNoResidueOnWorkerThread() throws Exception {
        LogContext.setRequestId("req-main");
        String[] inside = new String[2];
        String[] after = new String[2];
        Runnable first = LogContext.wrap(() -> inside[0] = LogContext.getRequestId());
        Runnable second = LogContext.wrap(() -> inside[1] = LogContext.getRequestId());

        Thread worker = new Thread(() -> {
            first.run();
            after[0] = LogContext.getRequestId();
            second.run();
            after[1] = LogContext.getRequestId();
        });
        worker.start();
        worker.join();

        assertEquals("req-main", inside[0]);
        assertEquals("req-main", inside[1]);
        assertNull(after[0], "任务结束后执行线程必须还原（此处原本无值 → 清空），否则池化线程会串号");
        assertNull(after[1]);
    }

    @Test
    void wrappedTaskKeepsWorkerThreadOwnRequestId() throws Exception {
        LogContext.setRequestId("req-main");
        String[] inside = new String[1];
        String[] after = new String[1];
        Runnable wrapped = LogContext.wrap(() -> inside[0] = LogContext.getRequestId());

        Thread worker = new Thread(() -> {
            LogContext.setRequestId("req-worker");
            wrapped.run();
            after[0] = LogContext.getRequestId();
        });
        worker.start();
        worker.join();

        assertEquals("req-main", inside[0], "任务体内应是捕获的值");
        assertEquals("req-worker", after[0], "任务结束后应还原执行线程原本的值（嵌套提交的场景）");
    }

    @Test
    void wrapOnlyWrapsAndNeverSchedulesAnything() {
        LogContext.setRequestId("req-main");
        String[] executedOn = new String[1];
        boolean[] sawRequestId = new boolean[1];
        Runnable wrapped = LogContext.wrap(() -> {
            executedOn[0] = Thread.currentThread().getName();
            sawRequestId[0] = "req-main".equals(LogContext.getRequestId());
        });

        assertNull(executedOn[0], "wrap 只做包装：不得在返回前执行任务体");

        wrapped.run();

        assertEquals(Thread.currentThread().getName(), executedOn[0],
                "执行仍发生在调用线程上（本周期不引入实际异步，只是备好传递机制）");
        assertTrue(sawRequestId[0]);
    }

    @Test
    void wrapRejectsNullTask() {
        assertThrows(NullPointerException.class, () -> LogContext.wrap(null));
    }

    // ==================== 与 RequestContext 的分工（D6） ====================

    @Test
    void logContextAndRequestContextDoNotInterfere() {
        String contextPathBefore = RequestContext.getContextPath();

        LogContext.setRequestId("req-main");
        RequestContext.setContextPath("/probe");
        assertEquals("req-main", LogContext.getRequestId(), "设置 context path 不得影响 reqId");

        // D6 的否决依据：内层 EncodingFilter 的 clear 先于最外层执行 → 两者的清理点必须分开
        LogContext.clear();
        assertEquals("/probe", RequestContext.getContextPath(),
                "LogContext.clear() 不得清除 context path（否则共用 clear() 会提前清掉 reqId）");

        RequestContext.clear();
        assertEquals(contextPathBefore, RequestContext.getContextPath(),
                "清理后应回落到默认 context path（本类行为自 D6 起零改动）");
    }
}
