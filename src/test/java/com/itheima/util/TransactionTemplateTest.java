package com.itheima.util;

import com.itheima.exception.DatabaseException;
import com.itheima.exception.ServerException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T11 定栈契约单测：{@link TransactionTemplate} 只在**包装点**（catch (SQLException)）记 SEVERE + 堆栈；
 * BusinessException / RuntimeException 分支**不记**（否则会与业务源头、{@code ExceptionFilter} 形成双栈）。
 *
 * <p>用**真实模板 + 真实连接池**（测试链路已具备 MySQL）；回调抛异常即触发对应分支，无需真造 DB 故障。
 * 真实"基础设施异常（连接池耗尽）"的等价用例见
 * {@code MyConnectionPoolTest#transactionTemplateLogsSevereWhenConnectionExhausted}（pool-test 执行）。
 */
class TransactionTemplateTest {

    private final TransactionTemplate template = new TransactionTemplate();

    private static List<LogRecord> withStack(LogProbe probe) {
        return probe.records().stream().filter(r -> r.getThrown() != null).toList();
    }

    @Test
    void sqlExceptionFromCallbackLogsSevereWithStackAndWrapsToDatabaseException() {
        Logger logger = LogUtil.getLogger(TransactionTemplate.class);
        LogProbe probe = LogProbe.attachTo(logger);
        try {
            DatabaseException ex = assertThrows(DatabaseException.class, () -> template.execute(conn -> {
                throw new SQLException("boom: select 1 from t");
            }));
            assertEquals("数据库操作失败", ex.getMessage(), "异常类型与文案不变（只动日志分工）");

            List<LogRecord> stacked = withStack(probe);
            assertEquals(1, stacked.size(), () -> "包装点应恰一条带堆栈记录: " + probe.records());
            assertEquals(Level.SEVERE, stacked.getFirst().getLevel());
            assertEquals("事务失败，数据库操作失败", stacked.getFirst().getMessage());
            assertFalse(stacked.getFirst().getMessage().contains("boom"), "日志消息不得含 SQL 文本/参数");
        } finally {
            probe.detach();
        }
    }

    @Test
    void businessExceptionFromCallbackIsSilent() {
        Logger logger = LogUtil.getLogger(TransactionTemplate.class);
        LogProbe probe = LogProbe.attachTo(logger);
        try {
            assertThrows(ServerException.class, () -> template.execute(conn -> {
                throw new ServerException("服务器异常");
            }));
            assertEquals(List.of(), probe.records(), "业务异常分支不记日志（业务链源头已自行持栈）");
        } finally {
            probe.detach();
        }
    }

    @Test
    void runtimeExceptionFromCallbackIsSilent() {
        Logger logger = LogUtil.getLogger(TransactionTemplate.class);
        LogProbe probe = LogProbe.attachTo(logger);
        try {
            assertThrows(IllegalStateException.class, () -> template.execute(conn -> {
                throw new IllegalStateException("编程错误");
            }));
            assertEquals(List.of(), probe.records(),
                    "RuntimeException 分支不记日志（业务路径由最终处理点 ExceptionFilter 带栈记录）");
        } finally {
            probe.detach();
        }
    }
}
