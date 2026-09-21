package com.itheima.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * 单行结构化日志 Formatter（周期 T1 / 任务 log-01 建；T2 / log-02 补入 {@code req=} 字段）。
 *
 * <p>输出形态（一条记录 = 一行，末尾 LF）：
 *
 * <pre>{@code ts=2026-09-21T19:30:12.345+08:00 level=INFO logger=com.itheima.util.LogUtil req=6a1b2c3d0f3e0001 msg=successfully load logs}</pre>
 *
 * <p>字段口径（NEEDS 4.0 D7「应用日志 = 时间 · reqId · 级别 · logger · 消息 · 异常堆栈」）：
 *
 * <ul>
 *   <li>{@code ts} —— 记录时间戳：本地时区、**固定 3 位毫秒**、带冒号偏移。取代既有
 *       {@code SimpleFormatter}（中文 locale 两行头、无毫秒、无时区 → 既难 grep 也无法排序）。</li>
 *   <li>{@code level} / {@code logger} —— 级别名（{@code Level.getName()}）与 logger 名。</li>
 *   <li>{@code req} —— 请求标识（T2 log-02 补入，取值见 {@link LogContext}）：**只在有值时才出现**，
 *       非请求线程 / 已 {@code clear()} 时该字段整段省略。</li>
 *   <li>{@code msg} —— 渲染后的消息：走 {@link Formatter#formatMessage}（与 {@code SimpleFormatter}
 *       同源，`{0}` 占位符仍由 MessageFormat 渲染，既有消息文案逐字不变）；其中的换行折成
 *       {@code \n} 字面量，以守住"一条记录一行"。</li>
 *   <li>异常堆栈 —— **跟在首行之后**（多行、不折行），行尾统一归一为 LF。</li>
 * </ul>
 *
 * <p>拼接顺序 = "固定字段（{@code ts} / {@code level} / {@code logger}）→ 可变字段（{@code req}）→
 * {@code msg}"，{@code msg} 置末（消息内含空格也不会破坏前面的字段）。{@code user=} 属**访问日志**字段
 * （D7），由 T3 在 access 输出端承载，不注入应用日志行。
 *
 * <p>本类无状态、不做级别过滤：级别由各 Handler 自己的 level 决定（见 {@link LogUtil}）。
 */
public class LogFormatter extends Formatter {

    /** 时间戳口径：本地时区 + 固定 3 位毫秒 + 带冒号偏移（如 2026-09-21T19:30:12.345+08:00）。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** 行分隔符固定 LF（不随平台变化）：日志是给人 grep、给 awk 抽字段的，分隔符必须稳定。 */
    private static final String LINE_SEPARATOR = "\n";

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("ts=").append(timestamp(record))
                .append(" level=").append(record.getLevel().getName())
                .append(" logger=").append(loggerName(record));
        appendRequestId(sb);
        sb.append(" msg=").append(singleLine(formatMessage(record)));
        appendThrowable(sb, record);
        return sb.append(LINE_SEPARATOR).toString();
    }

    private static String timestamp(LogRecord record) {
        return TIMESTAMP.format(record.getInstant().atZone(ZoneId.systemDefault()));
    }

    private static String loggerName(LogRecord record) {
        String name = record.getLoggerName();
        return name == null ? "" : name;
    }

    /**
     * {@code req=} 字段：{@link LogContext} 有值才输出（无值整段省略——既有"空值字段"会让 grep / awk
     * 抽取多一个噪声列，也让"clear 后不含 req="无法判定）。
     *
     * <p>为什么在 {@code format()} 里读 ThreadLocal 而不是把 reqId 塞进 {@code LogRecord}：本项目日志
     * **同步写**（D8），{@code format()} 就在打日志的那条线程上执行，读到的即该线程当前请求的标识；
     * 将来引入进程内线程池时，用 {@link LogContext#wrap(Runnable)} 在异步线程内恢复 reqId 即可继续成立，
     * 无需改动记录载体。
     *
     * <p>值一律过 {@link #singleLine}：reqId 由调用方设置（可能是任意字符串），仍是"一条记录一行"不变式
     * 的守门人。
     */
    private static void appendRequestId(StringBuilder sb) {
        String requestId = LogContext.getRequestId();
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        sb.append(" req=").append(singleLine(requestId));
    }

    /** 把消息里的换行折成 {@code \n} 字面量，保证"一条记录 = 一行"（CRLF 先处理，避免拆成两个转义）。 */
    private static String singleLine(String message) {
        if (message == null) {
            return "";
        }
        return message.replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n");
    }

    /** 异常堆栈跟在首行之后（多行保留，便于直接阅读）；行尾由平台分隔符归一为 LF。 */
    private static void appendThrowable(StringBuilder sb, LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown == null) {
            return;
        }
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            thrown.printStackTrace(writer);
        }
        sb.append(LINE_SEPARATOR).append(buffer.toString().replace("\r\n", LINE_SEPARATOR));
    }
}
