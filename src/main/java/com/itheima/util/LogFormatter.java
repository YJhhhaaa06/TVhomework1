package com.itheima.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * 单行结构化日志 Formatter（周期 T1 / 任务 log-01，NEEDS 4.0 D4 拍板 = 单行 key=value）。
 *
 * <p>输出形态（一条记录 = 一行，末尾 LF）：
 *
 * <pre>{@code ts=2026-09-21T19:30:12.345+08:00 level=INFO logger=com.itheima.util.LogUtil msg=successfully load logs}</pre>
 *
 * <p>字段口径（NEEDS 4.0 D7「应用日志 = 时间 · reqId · 级别 · logger · 消息 · 异常堆栈」）：
 *
 * <ul>
 *   <li>{@code ts} —— 记录时间戳：本地时区、**固定 3 位毫秒**、带冒号偏移。取代既有
 *       {@code SimpleFormatter}（中文 locale 两行头、无毫秒、无时区 → 既难 grep 也无法排序）。</li>
 *   <li>{@code level} / {@code logger} —— 级别名（{@code Level.getName()}）与 logger 名。</li>
 *   <li>{@code msg} —— 渲染后的消息：走 {@link Formatter#formatMessage}（与 {@code SimpleFormatter}
 *       同源，`{0}` 占位符仍由 MessageFormat 渲染，既有消息文案逐字不变）；其中的换行折成
 *       {@code \n} 字面量，以守住"一条记录一行"。</li>
 *   <li>异常堆栈 —— **跟在首行之后**（多行、不折行），行尾统一归一为 LF。</li>
 * </ul>
 *
 * <p>{@code req=} / {@code user=} 字段由后续任务 T2（{@code util/LogContext}）在同一行内补入，故此处
 * 按"固定字段 → 可变字段"顺序拼接、{@code msg} 置末（消息内含空格也不会破坏前面的字段）。
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
                .append(" logger=").append(loggerName(record))
                .append(" msg=").append(singleLine(formatMessage(record)));
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
