package com.itheima.mq;

import com.itheima.util.LogContext;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqDeliveryDispatcher} 单测（feed3-T31）：线程解耦（RT 不等 confirm）/ 队列满丢弃降级 /
 * 有界 drain / 关停后提交 / reqId 串联 / worker 存活。隔离手法：不依赖 broker（任务体自造），
 * 日志断言用 {@link LogProbe}。
 */
class MqDeliveryDispatcherTest {

    private MqDeliveryDispatcher dispatcher;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        probe = LogProbe.attachTo(LogUtil.getLogger(MqDeliveryDispatcher.class));
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.destroy(); // 幂等：已关停则直接返回
        }
        probe.detach();
    }

    @Test
    void runsTaskOnDedicatedWorkerThread() throws Exception {
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(dispatcher.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        }, "探针任务"));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals("mq-delivery", threadName.get(), "任务应跑在专用投递线程上");
        assertNotEquals(Thread.currentThread().getName(), threadName.get(), "不应占用调用方（Web）线程");
    }

    /** T31 验收留证（单元层）：worker 被占住时，提交立即返回——接口 RT 不含 confirm 等待。 */
    @Test
    void submitDoesNotWaitForSlowWorker() throws Exception {
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertTrue(dispatcher.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "慢任务（模拟 confirm 5s）"));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        assertTrue(dispatcher.submit(() -> { }, "快任务"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();

        assertTrue(elapsedMs < 200, "提交应在 worker 忙时立即返回，实测 " + elapsedMs + "ms");
    }

    @Test
    void queueFullDropsTaskWithWarningAndDoesNotThrow() throws Exception {
        dispatcher = new MqDeliveryDispatcher(1, 1000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(dispatcher.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "占位 worker"));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        AtomicBoolean queuedRan = new AtomicBoolean(false);
        assertTrue(dispatcher.submit(() -> queuedRan.set(true), "占位队列"), "容量=1，本条应入队");

        AtomicBoolean droppedRan = new AtomicBoolean(false);
        assertFalse(dispatcher.submit(() -> droppedRan.set(true), "被丢弃的那条"),
                "队列满应丢弃并返回 false");
        release.countDown();

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条队列满 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("队列已满"));
        assertTrue(warnings.getFirst().getMessage().contains("被丢弃的那条"), "丢弃日志应带任务描述便于定位");

        dispatcher.destroy(); // 有界 drain：等已入队任务跑完，再断言被丢弃的那条从未执行
        assertFalse(droppedRan.get(), "被丢弃的任务不应执行");
        assertTrue(queuedRan.get(), "已入队任务应正常执行");
    }

    @Test
    void drainProcessesQueuedTasksBeforeReturning() throws Exception {
        dispatcher = new MqDeliveryDispatcher(16, 5000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatcher.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "慢任务");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        AtomicInteger completed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertTrue(dispatcher.submit(completed::incrementAndGet, "存量" + i));
        }
        release.countDown();

        long t0 = System.nanoTime();
        dispatcher.destroy(); // drain：等队列存量投完
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(5, completed.get(), "drain 应把队列存量投完");
        assertTrue(elapsedMs < 5000, "drain 有上界、不应挂住关停，实测 " + elapsedMs + "ms");
        assertTrue(probe.atLevel(Level.WARNING).isEmpty(), "投完不应有丢弃 / 未投完告警");
    }

    @Test
    void drainTimeoutDropsRemainingWithWarning() throws Exception {
        dispatcher = new MqDeliveryDispatcher(16, 200);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatcher.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                // shutdownNow 会中断 worker：安静退出即可（残余②口径下本任务本身即"未投完"）
            }
        }, "慢任务");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        AtomicBoolean leftRan = new AtomicBoolean(false);
        assertTrue(dispatcher.submit(() -> leftRan.set(true), "滞留存量"));

        long t0 = System.nanoTime();
        dispatcher.destroy();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 2000, "drain 超时后必须返回、不得挂住关停，实测 " + elapsedMs + "ms");
        List<String> warnings = probe.messagesAtLevel(Level.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("未投完")),
                () -> "应记'未投完即退出'告警: " + warnings);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("剩余 1 条")),
                () -> "告警应带剩余条数: " + warnings);
        release.countDown();
        assertFalse(leftRan.get(), "未投完的存量不应再执行");
    }

    @Test
    void submitAfterShutdownIsRejectedQuietly() {
        dispatcher = new MqDeliveryDispatcher(8, 200);
        dispatcher.destroy();

        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean accepted = new AtomicBoolean(true);
        assertDoesNotThrowQuietly(() -> accepted.set(dispatcher.submit(() -> ran.set(true), "关停后提交")));

        assertFalse(accepted.get(), "已关停后提交应被拒绝");
        assertFalse(ran.get(), "被拒绝的任务不应执行");
        List<String> warnings = probe.messagesAtLevel(Level.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("已关停")),
                () -> "应记关停后丢弃告警: " + warnings);
    }

    private static void assertDoesNotThrowQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            throw new AssertionError("不应向业务线程抛异常", e);
        }
    }

    @Test
    void invalidConstructionFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new MqDeliveryDispatcher(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new MqDeliveryDispatcher(-1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new MqDeliveryDispatcher(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new MqDeliveryDispatcher(8, -1));
    }

    /** 任务抛未捕获异常只影响自身线程（stderr 有栈），队列消费必须自愈——不拖垮后续投递。 */
    @Test
    void workerSurvivesUnexpectedTaskException() throws Exception {
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        CountDownLatch boomDone = new CountDownLatch(1);
        assertTrue(dispatcher.submit(() -> {
            boomDone.countDown();
            throw new IllegalStateException("boom");
        }, "炸点"));
        assertTrue(boomDone.await(2, TimeUnit.SECONDS));

        CountDownLatch next = new CountDownLatch(1);
        assertTrue(dispatcher.submit(next::countDown, "后续任务"));
        assertTrue(next.await(2, TimeUnit.SECONDS), "worker 异常退出后应由替代线程继续消费队列");
    }

    /** LOG_CONVENTION §四"非 Web 线程"纪律 2：异步任务带提交线程的 reqId（LogContext.wrap）。 */
    @Test
    void propagatesRequestIdFromSubmittingThread() throws Exception {
        dispatcher = new MqDeliveryDispatcher(8, 1000);
        LogContext.setRequestId("req-test-0001");
        try {
            AtomicReference<String> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            assertTrue(dispatcher.submit(() -> {
                seen.set(LogContext.getRequestId());
                done.countDown();
            }, "req 探针"));
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals("req-test-0001", seen.get(), "异步任务应带提交线程的 reqId");
            assertEquals("req-test-0001", LogContext.getRequestId(), "还原后不得污染提交线程");
        } finally {
            LogContext.clear();
        }
    }
}
