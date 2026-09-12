package com.itheima.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleFlightTest {

    private final SingleFlight singleFlight = new SingleFlight();

    @Test
    void concurrentSameKeyLoadsOnlyOnce() throws Exception {
        int threads = 16;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderRelease = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var results = new java.util.concurrent.Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                results[i] = pool.submit(() -> {
                    start.await();
                    // loader 阻塞放大并发窗口：所有线程在同一时刻重叠在 get() 内
                    return singleFlight.get("k", () -> {
                        int n = loads.incrementAndGet();
                        loaderEntered.countDown();
                        loaderRelease.await();
                        return "v" + n;
                    });
                });
            }
            start.countDown();
            // 首个 loader 已进入阻塞，稍候让其余线程全部就位后放行（成功即移除，见 successRemovesEntryPreventingLeak）
            loaderEntered.await(5, TimeUnit.SECONDS);
            Thread.sleep(300);
            loaderRelease.countDown();
            for (int i = 0; i < threads; i++) {
                assertEquals("v1", results[i].get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loaderFailureRemovesEntryAllowingRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> singleFlight.get("k", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db down");
        }));

        // 失败已 remove：下次调用会重新执行 loader 而不是复用失败结果
        String value = singleFlight.get("k", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", value);
        assertEquals(2, calls.get());
    }

    @Test
    void successRemovesEntryPreventingLeak() {
        AtomicInteger calls = new AtomicInteger();

        String first = singleFlight.get("k", () -> {
            calls.incrementAndGet();
            return "v1";
        });
        assertEquals("v1", first);
        assertEquals(0, singleFlight.inFlightCount());

        // 成功已 remove：顺序第二次调用会再次执行 loader（不缓存已完成结果）
        String second = singleFlight.get("k", () -> {
            calls.incrementAndGet();
            return "v2";
        });
        assertEquals("v2", second);
        assertEquals(2, calls.get());
        assertEquals(0, singleFlight.inFlightCount());
    }

    @Test
    void distinctKeysRunIndependently() throws Exception {
        AtomicInteger loadsA = new AtomicInteger();
        AtomicInteger loadsB = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var fa = pool.submit(() -> {
                start.await();
                return singleFlight.get("a", () -> {
                    loadsA.incrementAndGet();
                    return "a-v";
                });
            });
            var fb = pool.submit(() -> {
                start.await();
                return singleFlight.get("b", () -> {
                    loadsB.incrementAndGet();
                    return "b-v";
                });
            });
            start.countDown();
            assertEquals("a-v", fa.get(5, TimeUnit.SECONDS));
            assertEquals("b-v", fb.get(5, TimeUnit.SECONDS));
            assertEquals(1, loadsA.get());
            assertEquals(1, loadsB.get());
        } finally {
            pool.shutdownNow();
        }
    }
}