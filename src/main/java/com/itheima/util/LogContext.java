package com.itheima.util;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求级日志上下文（周期 T2 / 任务 log-02，NEEDS 4.0 D6 拍板 = 方案 B：**新建本类、不动
 * {@link RequestContext}**）。
 *
 * <p>职责：承载"请求标识 reqId"，让同一次请求在各层产生的日志能被 {@code grep req=<id>} 串起来。
 * reqId 由 {@link LogFormatter} 在每条记录的行内输出为 {@code req=} 字段（无值时该字段整段不出现）。
 *
 * <p><b>与 {@link RequestContext} 的分工</b>（D6 的缓解口径；两侧注释交叉指向）：
 *
 * <ul>
 *   <li>约定：**日志类字段放本类**（reqId），**业务类字段放 {@code RequestContext}**（context path）。</li>
 *   <li>生命周期独立：{@code RequestContext} 由**内层** {@code EncodingFilter} 在 {@code try/finally} 中
 *       set/clear，其 {@code finally} **先于**外层 {@code ExceptionFilter} 的 {@code catch}、也先于最外层
 *       {@code AccessLogFilter} 的 {@code finally} 执行；**本类只在最外层 {@code AccessLogFilter}（T3）
 *       一处 set/clear**。若两者共用同一个 {@code clear()}，reqId 会在 {@code EncodingFilter} 退出时
 *       被清掉 → 异常日志（最需要 reqId 的场景）读不到。</li>
 *   <li>本类**无默认值兜底**（非请求线程读到 {@code null} 属预期），不同于 {@code RequestContext} 为服务
 *       启动期非请求线程准备的 {@code volatile} 默认 context path——这正是 D6 否决"合并为一个上下文类"的
 *       依据：两者语义不同族，合并属"形似合并、实为混装"。</li>
 * </ul>
 *
 * <p>本类**不依赖项目其它类**（只用 JDK），可被 Formatter / Filter / 单测自由使用。
 */
public final class LogContext {

    /** reqId 的固定长度（字符数）：8 位毫秒 + 4 位进程标识 + 4 位序号，见 {@link #newRequestId()}。 */
    public static final int REQUEST_ID_LENGTH = 16;

    /** 进程内自增序号（低位循环使用）：同毫秒内的并发/连续生成靠它区分，计数本身不做上限假设。 */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** 进程标识（类初始化时随机一次）：避免"重启后同毫秒 + 同序号"撞号。 */
    private static final String PROCESS_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private LogContext() {
    }

    /**
     * 生成请求标识（**唯一源**）：固定 {@link #REQUEST_ID_LENGTH} 字符 = 毫秒时间戳低 32 位（8 位十六进制，
     * 便于按时间粗排与肉眼区分）+ 进程标识（4 位）+ 进程内自增序号低 16 位（4 位）。
     *
     * <p>唯一性口径：同毫秒内的并发/连续调用靠**原子自增序号**区分（4 位约 6.5 万次/毫秒，远超本项目量级；
     * **超过该量级则序号回绕**，同毫秒内理论上可撞号——如实声明边界，本项目量级不可达）；
     * 跨进程重启靠**进程标识**区分——需"时间戳 + 进程标识 + 序号"三者同时相同才会撞号。
     *
     * <p>格式只在**本方法**内定义：调用方（T3 的 filter）只调本方法取值、不自造格式，避免生成口径分叉。
     */
    public static String newRequestId() {
        return String.format("%08x%s%04x",
                (int) System.currentTimeMillis(), PROCESS_TAG, SEQUENCE.incrementAndGet() & 0xFFFF);
    }

    /**
     * 绑定本次请求的标识（**只在最外层 filter 的进入处调用**，见类注释的分工口径）。
     *
     * <p>入参先归一：{@code null} 或去空白后为空 → 视作"清除"（不留下空值字段）；否则存**去空白后的值**
     * （reqId 内不得含空白，否则日志行的空白分隔会把它切碎）。
     */
    public static void setRequestId(String requestId) {
        String value = requestId == null ? null : requestId.trim();
        if (value == null || value.isEmpty()) {
            REQUEST_ID.remove();
            return;
        }
        REQUEST_ID.set(value);
    }

    /**
     * 当前线程的请求标识；**非请求线程（启动 / 定时 / 测试线程）返回 {@code null}**，此时日志行不输出
     * {@code req=} 字段（见 {@link LogFormatter}）。
     */
    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    /** 清除当前线程的请求标识（**只在 filter 的 finally 中调用**：请求结束必须清理，防线程复用串号）。 */
    public static void clear() {
        REQUEST_ID.remove();
    }

    /**
     * 请求上下文快照：捕获时刻的 reqId（{@code null} = 捕获线程本就没有请求上下文）。
     *
     * <p>不可变载体，可跨线程传递——它是"把调用线程的 reqId 交给异步线程"的唯一凭证。
     */
    public record Snapshot(String requestId) {
    }

    /** 捕获当前线程的 reqId 快照（**提交异步任务前**调用，D8 的"捕获"一步）。 */
    public static Snapshot capture() {
        return new Snapshot(REQUEST_ID.get());
    }

    /**
     * 把快照恢复到当前线程（**异步任务执行体开头**调用，D8 的"恢复"一步）；快照为 {@code null} 或空值时
     * **清空**当前线程——防池化线程残留在上一任务（尤其上一个请求）的 reqId 上串号。
     */
    public static void restore(Snapshot snapshot) {
        setRequestId(snapshot == null ? null : snapshot.requestId());
    }

    /**
     * 包装"需要带着**调用线程**的 reqId 执行"的任务（D8 要求的异步传递机制，本周期**无调用点**，机制先就位）。
     *
     * <p>语义 = 捕获（{@link #capture()}，在 **wrap 调用时**取值，即提交任务的那条线程）+ 恢复
     * （任务体开头 {@link #restore(Snapshot)}）+ 还原（任务结束后恢复执行线程**原本**的值，池化线程不留残留）。
     * 之所以要这一层：本项目日志**同步写**，{@code LogFormatter.format()} 在"打日志的那条线程"上执行并读
     * {@link #getRequestId()}，故将来 feed 流引入进程内线程池时，只要用本方法提交任务，异步线程的日志就仍
     * 带同一 reqId，整链不断裂（JUL 无内建 MDC，这是自建替代）。
     *
     * <p>**本方法只做包装、不创建线程、不持有执行器**——"机制先就位"不等于引入异步执行（D8）。
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Snapshot captured = capture();
        return () -> {
            Snapshot previous = capture();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }
}
