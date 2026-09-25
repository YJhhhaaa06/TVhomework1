package com.itheima.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T8（log2-08）审计记录器单测：行形态（纯函数）、记录写向审计专属 logger、
 * 以及"审计写失败不得影响业务"的守卫（红线）。
 *
 * <p>不碰 Redis / DB / HTTP：用**探针 Handler**（捕获 / 抛异常两种）挂到 logger 上、用完立刻摘除——
 * 同名 logger 是全 JVM 单例，残留会污染其它测试类。
 *
 * <p><b>JDK 事实（本次实测，非推断；依据 JDK 25 的 {@code java.logging/java/util/logging/Logger.java}
 * 中 {@code log(LogRecord)}）</b>：该方法只做 {@code handler.publish(record)}，**没有 try/catch** →
 * handler 抛出的异常会直接穿透给调用方（JUL 自带 Handler 是在自己的 {@code publish} 里兜 IO 异常，
 * 不是 Logger 兜）。故 {@link AuditLog#success} 的 try/catch 是**承重的**，下面用
 * {@link ThrowingHandler} 把这条前提断言出来，避免守卫用例退化为恒真。
 *
 * <p><b>产物影响</b>：守卫用例 ② 会让 {@code AuditLog.success} 经真实 {@code FileHandler} 在 **JUnit 链路
 * 日志目录**（{@code .stage8-target/test-logs/}）留下一条**合乎口径**的审计行（抛异常的探针 Handler 排在
 * FileHandler 之后，落盘先于抛异常）；前提探针 ① 已改挂**独立 logger**（关传播），不再产生不合规行。
 * 这是预期的测试链路产物，不写进生产 {@code logs/}（落点隔离见 LOG_CONVENTION 3.3）。
 */
class AuditLogTest {

    // ==================== 行形态（纯函数） ====================

    @Test
    void buildLineCarriesAllFieldsInFixedOrder() {
        assertEquals("action=admin.content.hide operatorId=13 target=contentId:42 result=success",
                AuditLog.buildLine("admin.content.hide", 13L, "contentId:42"),
                "字段顺序即实现顺序（pytest 侧按同一形态做正则断言）");
    }

    @Test
    void buildLineRendersMissingOperatorAsDash() {
        assertEquals("action=user.changePassword operatorId=- target=userId:7 result=success",
                AuditLog.buildLine("user.changePassword", null, "userId:7"),
                "操作者缺失（理论不可达，见 AuditLog#success 注释）应与访问日志 userId=- 同口径，"
                        + "不得把 null 写字面量落盘");
    }

    // ==================== 写向审计专属 logger，且恰好一条 ====================

    @Test
    void successWritesExactlyOneRecordToAuditLogger() {
        RecordingHandler probe = new RecordingHandler();
        Logger audit = LogUtil.getAuditLogger();
        audit.addHandler(probe);
        try {
            AuditLog.success("admin.comment.delete", 5L, "commentId:9");

            assertEquals(1, probe.records.size(), "一次调用只允许一条审计记录");
            LogRecord record = probe.records.get(0);
            assertEquals(Level.INFO, record.getLevel(), "审计行级别固定 INFO");
            assertEquals(LogUtil.AUDIT_LOGGER_NAME, record.getLoggerName(),
                    "记录必须写向审计专属 logger（写错 logger 就会进 system.log，失去独立保留价值）");
            assertEquals("action=admin.comment.delete operatorId=5 target=commentId:9 result=success",
                    record.getMessage(), "行 msg 的字段口径见 AuditLog 类注释");
            assertNull(record.getThrown(), "审计行不带异常堆栈（只记成功路径）");
        } finally {
            audit.removeHandler(probe);
        }
    }

    // ==================== 守卫：审计写失败不得影响业务（红线） ====================

    @Test
    void writeFailureIsSwallowedByGuardAndNeverReachesBusiness() {
        ThrowingHandler probe = new ThrowingHandler();

        // ① 前提探针：用**独立 logger**（关传播 + 用完摘除）证明"裸调用会被 handler 异常穿透"。
        //    不挂真实 audit logger —— 否则这条不合规的探针消息会被它的 FileHandler 写进 audit 输出文件。
        Logger probeLogger = Logger.getLogger("audit-guard-probe");
        probeLogger.setUseParentHandlers(false);
        probeLogger.addHandler(probe);
        try {
            assertThrows(IllegalStateException.class, () -> probeLogger.info("guard-is-load-bearing-probe"),
                    "若此处不抛，说明本 JDK 已自行兜底 handler 异常，本用例需改写（不留恒真断言）");
        } finally {
            probeLogger.removeHandler(probe);
        }
        assertEquals(1, probe.calls, "前提探针必须真的打到抛异常的 handler");

        // ② 守卫：同一个抛异常 handler 改挂**真实 audit logger**——记录到达该 handler 即代表写失败路径被触达
        Logger audit = LogUtil.getAuditLogger();
        audit.addHandler(probe);
        try {
            assertDoesNotThrow(() -> AuditLog.success("user.changePhone", 7L, "userId:7"),
                    "审计写失败必须被吞掉降级，绝不能把异常带给业务（红线）");
        } finally {
            audit.removeHandler(probe);
        }
        assertEquals(2, probe.calls, "第二次调用必须真的走到抛异常的 handler（守卫确实在写失败路径上）");
    }

    // ==================== 探针 ====================

    /** 捕获型探针：记下收到的记录，不落盘、不抛。 */
    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** 抛异常型探针：模拟"审计写入失败"（{@code Logger.log} 不兜 handler 异常 → 会穿透）。 */
    private static final class ThrowingHandler extends Handler {
        private int calls = 0;

        @Override
        public void publish(LogRecord record) {
            calls++;
            throw new IllegalStateException("审计写入失败（测试探针）");
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
