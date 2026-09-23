package com.itheima.util;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 日志探针（T9 立）：把写向某个 logger 的记录捕获下来，供"成功路径恰一条 / 失败路径 0 条"
 * 这类断言使用（先例 = {@code AuditLogTest} / {@code UserServiceTest#changePhoneSuccess} 的内联探针，
 * 本类把同一形态抽成共享件，避免在三个 service 单测里各写一遍）。
 *
 * <p><b>纪律</b>：同名 logger 是全 JVM 单例 —— 用完**必须** {@link #detach()}（或用
 * {@code try/finally}），否则残留的探针会污染其它测试类。探针只捕获**已经通过 logger 级别检查**的
 * 记录（handler 自身级别为默认 {@code ALL}），故它不能用于断言"某级别被过滤掉"。
 *
 * <p>本类不匹配 surefire 的默认 includes（{@code *Test} 等），不会被当作测试类执行。
 */
public final class LogProbe extends Handler {

    private final Logger target;
    private final List<LogRecord> records = new ArrayList<>();

    private LogProbe(Logger target) {
        this.target = target;
    }

    /** 挂到目标 logger 上；调用方负责 {@link #detach()}。 */
    public static LogProbe attachTo(Logger target) {
        LogProbe probe = new LogProbe(target);
        target.addHandler(probe);
        return probe;
    }

    /** 摘除探针（幂等：重复调用无副作用）。 */
    public void detach() {
        target.removeHandler(this);
    }

    /** 捕获到的全部记录（副本，避免调用方误改内部列表）。 */
    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    /** 指定级别的记录。 */
    public List<LogRecord> atLevel(Level level) {
        List<LogRecord> hits = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel().intValue() == level.intValue()) {
                hits.add(record);
            }
        }
        return hits;
    }

    /** 指定级别的记录消息（按写入顺序）。 */
    public List<String> messagesAtLevel(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : atLevel(level)) {
            messages.add(record.getMessage());
        }
        return messages;
    }

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
