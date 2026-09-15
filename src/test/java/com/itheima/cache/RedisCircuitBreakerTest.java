package com.itheima.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisCircuitBreaker 状态机单测（T1 cache-01）：纯逻辑、不碰真实 Redis，
 * 用包级构造器注入小阈值/短冷却驱动全状态迁移。
 */
class RedisCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    /** 冷却 500ms：给 sleep 断言留足余量，防 CI 负载抖动误判冷却期。 */
    private static final long COOLDOWN_MS = 500L;

    private final RedisCircuitBreaker breaker = new RedisCircuitBreaker(THRESHOLD, COOLDOWN_MS);

    @Test
    void closedByDefaultAndAllowsRequests() {
        assertEquals("CLOSED", breaker.stateName());
        assertTrue(breaker.tryAcquire());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold() {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertEquals("OPEN", breaker.stateName());
        assertFalse(breaker.tryAcquire());
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess(); // 清零连续计数
        for (int i = 0; i < THRESHOLD - 1; i++) {
            breaker.recordFailure();
        }
        assertEquals("CLOSED", breaker.stateName());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void openDeniesWithinCooldown() {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        // 冷却期内（50ms）拒绝
        assertFalse(breaker.tryAcquire());
        assertFalse(breaker.tryAcquire());
        assertEquals("OPEN", breaker.stateName());
    }

    @Test
    void afterCooldownFirstRequestBecomesSoleProbe() throws InterruptedException {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(COOLDOWN_MS + 150); // 冷却期满

        assertTrue(breaker.tryAcquire()); // 首个请求成为探针
        assertEquals("HALF_OPEN", breaker.stateName());
        assertFalse(breaker.tryAcquire()); // 其余请求仍被拒绝
        assertFalse(breaker.tryAcquire());
    }

    @Test
    void concurrentAcquireAfterCooldownAllowsExactlyOneProbe() throws InterruptedException {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(COOLDOWN_MS + 150);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (breaker.tryAcquire()) {
                    allowed.incrementAndGet();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, allowed.get(), "半开状态下并发抢闸应恰有一个探针放行");
    }

    @Test
    void probeSuccessClosesCircuit() throws InterruptedException {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(COOLDOWN_MS + 150);
        assertTrue(breaker.tryAcquire()); // 探针
        breaker.recordSuccess();          // 探针成功

        assertEquals("CLOSED", breaker.stateName());
        assertTrue(breaker.tryAcquire()); // 恢复正常放行
    }

    @Test
    void probeFailureReopensAndResetsCooldown() throws InterruptedException {
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(COOLDOWN_MS + 150);
        assertTrue(breaker.tryAcquire()); // 探针
        breaker.recordFailure();          // 探针失败 → 重开

        assertEquals("OPEN", breaker.stateName());
        assertFalse(breaker.tryAcquire()); // 冷却已重置，立即再拒
        // 等满冷却后可再次探针
        Thread.sleep(COOLDOWN_MS + 150);
        assertTrue(breaker.tryAcquire());
    }
}
