package com.itheima.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1（log-01）单行结构化 Formatter 单测：字段齐全 / 单行不变式 / 毫秒与时区 / 消息渲染同源 /
 * 异常堆栈跟随消息。纯组件测试，无外部依赖。
 */
class LogFormatterTest {

    /** 时间戳断言与实现同口径（3 位毫秒 + 带冒号偏移），但由 JDK 独立格式化验证，非复制实现。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final LogFormatter formatter = new LogFormatter();

    private static LogRecord record(Level level, String loggerName, String message, long millis) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(loggerName);
        record.setMillis(millis);
        return record;
    }

    @Test
    void formatsAllFieldsOnASingleLine() {
        LogRecord record = record(Level.INFO, "com.itheima.demo.Service", "列表刷不出来", 1_700_000_000_123L);

        String formatted = formatter.format(record);
        String head = formatted.substring(0, formatted.length() - 1);

        assertTrue(formatted.endsWith("\n"), "结果应以 LF 结尾（StreamHandler 不补分隔符）: " + formatted);
        assertFalse(head.contains("\n"), "一条记录必须只占一行: " + head);
        assertTrue(formatted.startsWith("ts="), formatted);
        assertTrue(formatted.contains(" level=INFO "), formatted);
        assertTrue(formatted.contains(" logger=com.itheima.demo.Service "), formatted);
        assertTrue(formatted.endsWith(" msg=列表刷不出来\n"), formatted);
    }

    @Test
    void timestampCarriesMillisecondAndZoneOffset() {
        long millis = 1_700_000_000_123L;
        String expected = TIMESTAMP.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));

        String formatted = formatter.format(record(Level.INFO, "l", "m", millis));

        assertTrue(formatted.startsWith("ts=" + expected + " level="),
                "时间戳应含 3 位毫秒与带冒号时区偏移: " + formatted);
    }

    @Test
    void timestampKeepsThreeMillisecondDigitsWhenZero() {
        String formatted = formatter.format(record(Level.INFO, "l", "m", 1_700_000_000_000L));

        // strip 掉结尾的 LF：matches() 要求整串匹配，而默认模式下 . 不匹配换行
        assertTrue(formatted.strip().matches("ts=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.000[+\\-]\\d{2}:\\d{2} .*"),
                "毫秒为 0 时也必须保留 3 位（不得退化为无小数）: " + formatted);
    }

    @Test
    void multilineMessageCollapsedIntoOneLine() {
        String formatted = formatter.format(record(Level.WARNING, "l", "第一行\n第二行\r\n第三行", 1L));
        String head = formatted.substring(0, formatted.length() - 1);

        assertFalse(head.contains("\n"), "多行消息必须折成一行: " + head);
        assertFalse(head.contains("\r"), head);
        assertTrue(head.endsWith("msg=第一行\\n第二行\\n第三行"), head);
    }

    @Test
    void messageParametersRenderedLikeSimpleFormatter() {
        LogRecord record = record(Level.INFO, "l", "user={0}, id={1}", 1L);
        record.setParameters(new Object[]{"alice", 42});

        String formatted = formatter.format(record);

        assertTrue(formatted.endsWith(" msg=user=alice, id=42\n"),
                "占位符渲染应与 SimpleFormatter 同源（MessageFormat）: " + formatted);
    }

    @Test
    void levelsAreRenderedByName() {
        assertTrue(formatter.format(record(Level.SEVERE, "l", "m", 1L)).contains(" level=SEVERE "));
        assertTrue(formatter.format(record(Level.FINE, "l", "m", 1L)).contains(" level=FINE "));
    }

    @Test
    void throwableStackTraceFollowsMessageLine() {
        LogRecord record = record(Level.SEVERE, "l", "缓存读失败", 1L);
        record.setThrown(new IllegalStateException("redis down"));

        String formatted = formatter.format(record);
        String[] lines = formatted.split("\n", -1);

        assertTrue(lines[0].endsWith("msg=缓存读失败"), "首行应为结构化字段行: " + lines[0]);
        assertTrue(lines.length > 1, "异常堆栈应跟在首行之后: " + formatted);
        assertTrue(formatted.contains("java.lang.IllegalStateException: redis down"), formatted);
        assertTrue(formatted.contains("at " + getClass().getName()), "堆栈应含调用点: " + formatted);
        assertFalse(formatted.contains("\r"), "行尾应统一归一为 LF: " + formatted);
    }
}
