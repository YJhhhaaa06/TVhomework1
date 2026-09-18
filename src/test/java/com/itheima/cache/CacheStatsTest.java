package com.itheima.cache;

import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 观测埋点统计组件单测：域解析 / 六类计数 / 惰性日志输出触发 / 打点自身异常吞掉。
 * 纯组件测试，不依赖 Redis（CacheStats 无任何外部依赖）。
 */
class CacheStatsTest {

    // ==================== CacheKeys.domainOf 域解析（key 生成与解析同源） ====================

    @Test
    void domainOfMapsAllKeyShapes() {
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("content:1"));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf("content:comments:1"));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("content:index:1:2"));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("content:index:-1:-1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("content:likeCount:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("content:likeSet:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("comment:likeCount:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("comment:likeSet:1"));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf("user:following:1"));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf("user:follower:1"));
        assertEquals(CacheDomain.OTHER, CacheKeys.domainOf("unknown:prefix:1"));
        assertEquals(CacheDomain.OTHER, CacheKeys.domainOf(null));
    }

    @Test
    void domainOfUnwrapsEmptyMarkerToInnerDataKeyDomain() {
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("empty:content:1"));
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf("empty:content:comments:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("empty:content:likeSet:1"));
        assertEquals(CacheDomain.FOLLOW, CacheKeys.domainOf("empty:user:following:1"));
    }

    @Test
    void domainOfLongPrefixWinsOverGenericContent() {
        // 前缀重叠：content: 是 content:comments:/content:index:/content:like* 的公共前缀，长前缀必须优先
        assertEquals(CacheDomain.COMMENT, CacheKeys.domainOf("content:comments:1"));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("content:index:1:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("content:likeCount:1"));
        assertEquals(CacheDomain.LIKE, CacheKeys.domainOf("content:likeSet:1"));
        assertEquals(CacheDomain.CONTENT, CacheKeys.domainOf("content:1"));
    }

    // ==================== 计数 ====================

    @Test
    void recordCountsByDomainAndEvent() {
        CacheStats stats = new CacheStats();

        stats.record(CacheStats.Event.HIT_DATA, "content:1");
        stats.record(CacheStats.Event.HIT_DATA, "content:2");
        stats.record(CacheStats.Event.MISS, "content:3");
        stats.record(CacheStats.Event.LOAD, "content:3");
        stats.record(CacheStats.Event.DEGRADE, "content:4");
        stats.record(CacheStats.Event.WRITE_FAIL, "content:5");

        assertEquals(2, stats.count(CacheDomain.CONTENT, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.MISS));
        assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.LOAD));
        assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.DEGRADE));
        assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.WRITE_FAIL));
        assertEquals(6, stats.totalAccesses());
        // 其它域不受污染
        assertEquals(0, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_DATA));
    }

    @Test
    void recordBucketsByDomain() {
        CacheStats stats = new CacheStats();

        stats.record(CacheStats.Event.HIT_DATA, "content:1");      // CONTENT
        stats.record(CacheStats.Event.HIT_DATA, "content:comments:1"); // COMMENT
        stats.record(CacheStats.Event.HIT_DATA, "content:likeSet:1");  // LIKE
        stats.record(CacheStats.Event.HIT_DATA, "user:following:1");   // FOLLOW
        stats.record(CacheStats.Event.HIT_DATA, "zoo:1");              // OTHER

        assertEquals(1, stats.count(CacheDomain.CONTENT, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.COMMENT, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.LIKE, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.FOLLOW, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.HIT_DATA));
    }

    // ==================== 惰性日志输出（T7 红线：不引入定时器/端点） ====================

    private Logger statsLogger;
    private List<Handler> addedHandlers = new ArrayList<>();

    @BeforeEach
    void captureLogger() {
        statsLogger = LogUtil.getLogger(CacheStats.class);
    }

    @AfterEach
    void releaseLogger() {
        for (Handler h : addedHandlers) {
            statsLogger.removeHandler(h);
        }
        addedHandlers.clear();
        statsLogger.setLevel(null); // 复位继承根级别
    }

    private CaptureHandler attachCaptureHandler() {
        CaptureHandler capture = new CaptureHandler();
        capture.setLevel(Level.ALL);
        statsLogger.addHandler(capture);
        statsLogger.setLevel(Level.INFO);
        addedHandlers.add(capture);
        return capture;
    }

    @Test
    void lazyLogFiresOncePerLogInterval() {
        CaptureHandler capture = attachCaptureHandler();
        CacheStats stats = new CacheStats(2); // 每 2 次记录输出一次摘要

        stats.record(CacheStats.Event.HIT_DATA, "content:1"); // total=1：未到阈值
        assertFalse(capture.containsSummary(), "第 1 次记录不应触发摘要");

        stats.record(CacheStats.Event.MISS, "content:2"); // total=2：触发
        assertTrue(capture.containsSummary(), "第 2 次记录应触发摘要");

        stats.record(CacheStats.Event.HIT_EMPTY, "content:3"); // total=3：未到阈值
        assertEquals(1, capture.summaryCount(), "阈值后第 1 次记录不应再次触发");
    }

    @Test
    void lazyLogSummaryContainsPerDomainCounts() {
        CaptureHandler capture = attachCaptureHandler();
        CacheStats stats = new CacheStats(1); // 每次记录都输出

        stats.record(CacheStats.Event.HIT_DATA, "content:1");

        assertEquals(1, capture.summaryCount());
        String summary = capture.latestSummary();
        assertTrue(summary.contains("total="), "摘要应含 total 计数: " + summary);
        assertTrue(summary.contains("content{"), "摘要应含 content 域桶: " + summary);
        assertTrue(summary.contains("hitData="), "摘要应含 hitData 事件: " + summary);
        assertTrue(summary.contains("miss="), "摘要应含 miss 事件: " + summary);
    }

    // ==================== 打点自身异常吞掉（红线：不影响主链路） ====================

    @Test
    void recordNeverThrowsOnUnexpectedInput() {
        CacheStats stats = new CacheStats();
        assertDoesNotThrow(() -> stats.record(CacheStats.Event.HIT_DATA, null)); // null → OTHER 兜底，正常计数
        assertDoesNotThrow(() -> stats.record(CacheStats.Event.MISS, ""));
        assertDoesNotThrow(() -> stats.record(null, "content:1")); // null 事件 → 内部 NPE 被吞，不抛

        assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.HIT_DATA));
        assertEquals(1, stats.count(CacheDomain.OTHER, CacheStats.Event.MISS));
        assertEquals(2, stats.totalAccesses()); // null 事件那次失败在计数前即被吞掉
    }

    // ==================== 捕获 Handler ====================

    private static final class CaptureHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean containsSummary() {
            return messages.stream().anyMatch(m -> m.contains("CacheStats 摘要"));
        }

        int summaryCount() {
            return (int) messages.stream().filter(m -> m.contains("CacheStats 摘要")).count();
        }

        String latestSummary() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).contains("CacheStats 摘要")) {
                    return messages.get(i);
                }
            }
            return "";
        }
    }
}