package com.itheima.filter;

import com.itheima.config.AppConfig;
import com.itheima.util.LogContext;
import com.itheima.util.LogUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * 访问日志（周期 T3 / 任务 log-03，NEEDS 4.0 D3）：注册于 {@code web.xml} **最外层**
 * （在 {@link ExceptionFilter} 之前声明），为**每个请求**在 access 输出端落一行，
 * 字段口径见 NEEDS 4.0 D7：时间 · reqId · method · path · userId · 耗时(ms) · 结果码 · 慢请求标记。
 *
 * <p>行形态（复用 {@code LogFormatter}，msg 内为访问日志专属 key=value）：
 * <pre>{@code ts=... level=INFO logger=access req=... msg=method=GET path=/user/login userId=13 code=200 cost=23ms slow=0}</pre>
 *
 * <ul>
 *   <li><b>reqId</b>：本 filter 是**唯一**的 set/clear 点（D6）——进入时
 *       {@code LogContext.setRequestId(newRequestId())}、finally 中 {@code clear()}（连同结果码）。
 *       因为 ExceptionFilter / EncodingFilter 位于其内层，它们的异常 catch 与 RequestContext.clear
 *       都先于本 finally 执行，reqId 与结果码必须由本层收口，异常日志才能读到标识。</li>
 *   <li><b>结果码</b>：由 {@code BaseServletUtil.writeSuccess/writeError} 写入
 *       {@code LogContext}（D5 收口，不包装 HttpServletResponse）；本项目响应恒为
 *       <b>HTTP 200 + body code</b>，故不得以 HTTP status 判成败。缺省 0 = 未走业务统一出口
 *       （静态资源 / OPTIONS 预检 / 未映射 404）。</li>
 *   <li><b>耗时</b>：{@code System.nanoTime} 于本层入口起、finally 中结算，覆盖整条 filter 链
 *       + servlet；单位 ms（整除）。</li>
 *   <li><b>慢请求标记</b>：{@code cost >= log.slowRequestMs}（默认 1000ms，可配）→ {@code slow=1}，
 *       否则 {@code slow=0}（打标记，不另起一行、不设独立性能日志文件——D7）。</li>
 *   <li><b>脱敏口径</b>（D7"绝不记"）：path 取自 {@code getRequestURI()} 去掉 contextPath
 *       （servlet 规范保证**不含 query 串**）；**不记** query 串 / header / 请求体
 *       （token / 手机号明文 / 密码一律不落盘——漏识别即泄密的隐患一并消除）。</li>
 * </ul>
 */
public class AccessLogFilter implements Filter {

    /** 访问输出端专属 logger（LogUtil 装配，只进 access.log，见 LogUtil 类注释）。 */
    private static final Logger ACCESS_LOGGER = LogUtil.getAccessLogger();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        long start = System.nanoTime();
        LogContext.setRequestId(LogContext.newRequestId());
        try {
            chain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            String path = req.getRequestURI().substring(req.getContextPath().length());
            Object userIdAttr = req.getAttribute("userId");
            Long userId = (userIdAttr instanceof Long) ? (Long) userIdAttr : null;
            ACCESS_LOGGER.info(buildLine(req.getMethod(), path, userId,
                    LogContext.getResultCode(), costMs, AppConfig.getLogSlowRequestMs()));
            LogContext.clear();
        }
    }

    /**
     * 访问行 msg 内容（**纯函数**，JUnit 直测）：{@code method=… path=… userId=… code=… cost=…ms slow=0|1}。
     * {@code userId == null}（未登录 / 非业务路径）→ {@code user=-}；{@code slow} = 耗时是否达到阈值。
     */
    static String buildLine(String method, String path, Long userId, int code,
                            long costMs, long slowThresholdMs) {
        return "method=" + method
                + " path=" + path
                + " userId=" + (userId == null ? "-" : userId)
                + " code=" + code
                + " cost=" + costMs + "ms"
                + " slow=" + (costMs >= slowThresholdMs ? 1 : 0);
    }
}