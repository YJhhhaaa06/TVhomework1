package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.feed.model.dto.FeedPushMessage;
import com.itheima.mq.MqConsumerContainer;
import com.itheima.mq.MqMessageHandler;
import com.itheima.mq.MqTopology;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FeedPushConsumer} 单测（feed1-18 T18）：注册 / init 不抛 / 解码委派 / 失败语义 / 通配前向兼容。
 *
 * <p>隔离手法：mock {@link MqConsumerContainer} 与 {@link FeedInboxCache}，{@link JacksonCodec} 用真实件
 * （覆盖 record 的 JSON 往返），**不依赖真实 broker**。
 */
class FeedPushConsumerTest {

    private MqConsumerContainer container;
    private FeedInboxCache inboxCache;
    private FeedPushConsumer consumer;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        container = mock(MqConsumerContainer.class);
        inboxCache = mock(FeedInboxCache.class);
        consumer = new FeedPushConsumer(container, new JacksonCodec(), inboxCache);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedPushConsumer.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    @Test
    void initRegistersPushQueueHandler() {
        consumer.init();

        ArgumentCaptor<MqMessageHandler> handler = ArgumentCaptor.forClass(MqMessageHandler.class);
        verify(container).register(eq(MqTopology.QUEUE_PUSH), handler.capture());
        assertNotNull(handler.getValue(), "注册的处理器不得为空");
    }

    @Test
    void initSwallowsRegistrationFailure() {
        // IoC 红线：init() 异常会被包成 RuntimeException 上抛并阻断 Tomcat 启动 → 注册失败必须降级
        doThrow(new IllegalStateException("boom")).when(container).register(anyString(), any());

        assertDoesNotThrow(() -> consumer.init());

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("写扩散消费者注册失败"));
        assertNotNull(warnings.getFirst().getThrown(), "注册失败是该链唯一捕获点 → 必须持栈");
    }

    @Test
    void handleDecodesPayloadAndFansOut() {
        consumer.handle(MqTopology.RK_PUSH_CONTENT, body(42L, 9L));

        verify(inboxCache).fanout(42L, 9L);
    }

    @Test
    void handleRejectsMalformedJson() {
        // 非法 JSON → 抛出（由容器根捕获记 SEVERE + 栈后一次性转死信），本类不吞、不二次记栈
        assertThrows(RuntimeException.class, () -> consumer.handle(MqTopology.RK_PUSH_CONTENT,
                "{not-json".getBytes(StandardCharsets.UTF_8)));
        verify(inboxCache, never()).fanout(anyLong(), anyLong());
        assertTrue(probe.records().isEmpty(), "本类不重复记录消费失败（容器是持栈点）");
    }

    @Test
    void handleRejectsEmptyBody() {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.handle(MqTopology.RK_PUSH_CONTENT, null));
        assertThrows(IllegalArgumentException.class,
                () -> consumer.handle(MqTopology.RK_PUSH_CONTENT, new byte[0]));
        verify(inboxCache, never()).fanout(anyLong(), anyLong());
    }

    @Test
    void handleIgnoresUnknownRoutingKey() {
        // feed.push.queue 以 feed.push.# 通配绑定：将来新增子类型会一并投到本队列 → 不误解析、不投死信
        assertDoesNotThrow(() -> consumer.handle("feed.push.other", body(1L, 2L)));

        verify(inboxCache, never()).fanout(anyLong(), anyLong());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    private static byte[] body(long contentId, long authorId) {
        String json = new JacksonCodec().toJson(new FeedPushMessage(contentId, authorId));
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
