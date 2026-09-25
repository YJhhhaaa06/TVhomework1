package com.itheima.filter;

import com.itheima.config.AppConfig;
import com.itheima.util.LogContext;
import com.itheima.util.LogUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3（log-03）访问日志 filter 单测：记录捕获 + 行字段（method/path/userId/code/cost/slow 由
 * {@link AccessLogFilter#buildLine} 纯函数承载）+ 最外层 set/clear 收口（reqId 与结果码）+ 隐私
 * 口径（不读 query/header/body）。纯组件测试 + Mockito，不碰真实容器。
 */
class AccessLogFilterTest {

    /** 捕获 access logger 记录的探针 handler：publish 即收集，测完移除，不影响真实文件输出端。 */
    private final List<LogRecord> captured = new ArrayList<>();

    private Handler probeHandler;

    private final AccessLogFilter filter = new AccessLogFilter();

    @BeforeEach
    void attachProbe() {
        Logger access = LogUtil.getAccessLogger();
        probeHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        };
        probeHandler.setLevel(Level.ALL);
        access.addHandler(probeHandler);
    }

    @AfterEach
    void detachProbeAndClearContext() {
        LogUtil.getAccessLogger().removeHandler(probeHandler);
        LogContext.clear();
    }

    private static HttpServletRequest req(String method, String uri, String contextPath, Object userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getContextPath()).thenReturn(contextPath);
        when(req.getAttribute("userId")).thenReturn(userId);
        return req;
    }

    /** chain：注册回调（如模拟控制器写结果码），并返回本身走完。 */
    private static FilterChain chain(Runnable inside) throws Exception {
        return (request, response) -> {
            if (inside != null) {
                inside.run();
            }
        };
    }

    private static HttpServletResponse resp() {
        return mock(HttpServletResponse.class);
    }

    // ==================== 行字段（doFilter 全链路） ====================

    @Test
    void successPathWritesOneLineWithAllFieldsAndClearsContext() throws Exception {
        HttpServletRequest req = req("GET", "/ctx/user/login", "/ctx", 13L);
        AtomicReference<String> reqIdInChain = new AtomicReference<>();

        filter.doFilter(req, resp(), chain(() -> {
            // 最外层 filter 进入后、链内已有 reqId（T2 set/clear 驱动点）
            reqIdInChain.set(LogContext.getRequestId());
            // 模拟控制器经 BaseServletUtil.writeSuccess 收口结果码
            LogContext.setResultCode(200);
        }));

        assertEquals(1, captured.size(), "一次请求只落一行: " + captured);
        LogRecord record = captured.get(0);
        assertEquals("access", record.getLoggerName(), "access 行应写向专属 logger");
        String msg = record.getMessage();
        assertTrue(msg.startsWith("method=GET "), msg);
        assertTrue(msg.contains(" path=/user/login "), msg);
        assertTrue(msg.contains(" userId=13 "), msg);
        assertTrue(msg.contains(" code=200 "), msg);
        assertTrue(msg.matches(".* cost=\\d+ms .*"), "耗时字段应为整数 ms: " + msg);
        assertTrue(msg.endsWith(" slow=0"), "本地 ms 级请求不应带慢标记: " + msg);

        // 最外层 filter finally 收口：reqId 与结果码一并清空
        assertNull(LogContext.getRequestId(), "doFilter 返回后 reqId 必须清除");
        assertEquals(0, LogContext.getResultCode(), "doFilter 返回后结果码必须清除");

        // 链内能读到 16 位十六进制 reqId（T2 生成口径）
        String insideReqId = reqIdInChain.get();
        assertTrue(insideReqId != null && insideReqId.matches("[0-9a-f]{16}"),
                "链内应持有固定 16 位 hex 的 reqId: " + insideReqId);
    }

    @Test
    void pathExcludesContextPrefix() throws Exception {
        HttpServletRequest req = req("GET", "/app/api/content/1", "/app", null);

        filter.doFilter(req, resp(), chain(null));

        assertTrue(captured.get(0).getMessage().contains(" path=/api/content/1 "),
                "path 应为去掉 contextPath 后的路径: " + captured.get(0).getMessage());
    }

    @Test
    void anonymousRequestRendersUserIdDash() throws Exception {
        HttpServletRequest req = req("GET", "/index.html", "", null);

        filter.doFilter(req, resp(), chain(null));

        assertTrue(captured.get(0).getMessage().contains(" userId=- "),
                "未登录/静态资源 userId 应为 -: " + captured.get(0).getMessage());
    }

    @Test
    void businessErrorCodePropagatesIntoLine() throws Exception {
        HttpServletRequest req = req("POST", "/user/register", "", null);

        // 模拟服务层抛 DuplicatePhoneException → ExceptionFilter catch → BaseServletUtil.writeError(409)
        filter.doFilter(req, resp(), chain(() -> LogContext.setResultCode(409)));

        assertTrue(captured.get(0).getMessage().contains(" code=409 "),
                "业务异常结果码应与响应体 code 一致: " + captured.get(0).getMessage());
    }

    @Test
    void slowRequestGetsMarkerWhenThresholdMet() throws Exception {
        // 阈值由 AppConfig 按配置读取（log.slowRequestMs）。断言"阈值可配且对 filter 生效"：
        // 把该键置 0 → cost>=0 恒成立 → 必带 slow=1；与默认 1000 下"0/1ms → slow=0"
        // （见 successPathWritesOneLineWithAllFields)互为对照。改 PROPS 用 LogUtilTest 同款反射口径。
        replaceSlowThreshold(0);
        try {
            filter.doFilter(req("GET", "/probe", "", null), resp(), chain(null));

            assertTrue(captured.get(0).getMessage().endsWith(" slow=1"),
                    "cost 达到阈值应带 slow=1: " + captured.get(0).getMessage());
        } finally {
            restoreSlowThreshold();
        }
    }

    // ==================== 隐私口径：不读 query / header / 请求体 ====================

    @Test
    void neverReadsQueryStringHeadersOrParameters() throws Exception {
        HttpServletRequest req = req("GET", "/x", "", null);

        filter.doFilter(req, resp(), chain(null));

        verify(req, never()).getQueryString();
        verify(req, never()).getHeader(anyString());
        verify(req, never()).getParameter(anyString());
    }

    // ==================== buildLine 纯函数 ====================

    @Test
    void buildLineRendersAllFieldsInOrder() {
        String line = AccessLogFilter.buildLine("POST", "/user/login", 13L, 200, 23, 1000);

        assertEquals("method=POST path=/user/login userId=13 code=200 cost=23ms slow=0", line);
    }

    @Test
    void buildLineSlowBoundaryIsInclusive() {
        assertEquals("slow=0", tailSlower(AccessLogFilter.buildLine("GET", "/x", null, 0, 999, 1000)));
        assertEquals("slow=1", tailSlower(AccessLogFilter.buildLine("GET", "/x", null, 0, 1000, 1000)));
        assertEquals("slow=1", tailSlower(AccessLogFilter.buildLine("GET", "/x", null, 0, 5001, 1000)));
    }

    @Test
    void buildLineUserNullRendersDash() {
        assertTrue(AccessLogFilter.buildLine("GET", "/x", null, 0, 1, 1000).contains(" userId=- "));
        assertFalse(AccessLogFilter.buildLine("GET", "/x", 7L, 0, 1, 1000).contains(" userId=- "));
    }

    // ==================== 工具（AppConfig.PROPS 反射改写，同 LogUtilTest 口径） ====================

    private static String tailSlower(String line) {
        return line.substring(line.lastIndexOf("slow="));
    }

    private static Properties propsField() throws Exception {
        Field field = AppConfig.class.getDeclaredField("PROPS");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static void replaceSlowThreshold(int slowRequestMs) throws Exception {
        Properties props = propsField();
        props.setProperty("log.slowRequestMs", String.valueOf(slowRequestMs)); // 仅改阈值键，其余保持
    }

    private static void restoreSlowThreshold() throws Exception {
        propsField().remove("log.slowRequestMs"); // 键缺失时 getInt 走默认 1000
    }
}