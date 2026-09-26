package com.itheima.feed.service;

import com.itheima.cache.JacksonCodec;
import com.itheima.follow.model.dto.InboxRebuildMessage;
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
 * {@link FeedRebuildConsumer} 单测（feed1-19 T19）：注册 / init 不抛 / 解码委派 / 失败语义 / 通配前向兼容。
 *
 * <p>隔离手法：mock {@link MqConsumerContainer} 与 {@link FeedRebuildService}，{@link JacksonCodec}
 * 用真实件（覆盖 record 的 JSON 往返），**不依赖真实 broker**。
 */
class FeedRebuildConsumerTest {

    private MqConsumerContainer container;
    private FeedRebuildService rebuildService;
    private FeedRebuildConsumer consumer;
    private LogProbe probe;

    @BeforeEach
    void setUp() {
        container = mock(MqConsumerContainer.class);
        rebuildService = mock(FeedRebuildService.class);
        consumer = new FeedRebuildConsumer(container, new JacksonCodec(), rebuildService);
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedRebuildConsumer.class));
    }

    @AfterEach
    void tearDown() {
        probe.detach();
    }

    @Test
    void initRegistersRebuildQueueHandler() {
        consumer.init();

        ArgumentCaptor<MqMessageHandler> handler = ArgumentCaptor.forClass(MqMessageHandler.class);
        verify(container).register(eq(MqTopology.QUEUE_REBUILD), handler.capture());
        assertNotNull(handler.getValue(), "注册的处理器不得为空");
    }

    @Test
    void initSwallowsRegistrationFailure() {
        // IoC 红线：init() 异常会被包成 RuntimeException 上抛并阻断 Tomcat 启动 → 注册失败必须降级
        doThrow(new IllegalStateException("boom")).when(container).register(anyString(), any());

        assertDoesNotThrow(() -> consumer.init());

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("收件箱重建消费者注册失败"));
        assertNotNull(warnings.getFirst().getThrown(), "注册失败是该链唯一捕获点 → 必须持栈");
    }

    @Test
    void handleDecodesPayloadAndRebuildsInbox() {
        consumer.handle(MqTopology.RK_REBUILD_INBOX, body(7L));

        verify(rebuildService).rebuildInbox(7L);
    }

    @Test
    void handleRejectsMalformedJson() {
        // 非法 JSON → 抛出（由容器根捕获记 SEVERE + 栈后一次性转死信），本类不吞、不二次记栈
        assertThrows(RuntimeException.class, () -> consumer.handle(MqTopology.RK_REBUILD_INBOX,
                "{not-json".getBytes(StandardCharsets.UTF_8)));
        verify(rebuildService, never()).rebuildInbox(anyLong());
        assertTrue(probe.records().isEmpty(), "本类不重复记录消费失败（容器是持栈点）");
    }

    @Test
    void handleRejectsEmptyBody() {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.handle(MqTopology.RK_REBUILD_INBOX, null));
        assertThrows(IllegalArgumentException.class,
                () -> consumer.handle(MqTopology.RK_REBUILD_INBOX, new byte[0]));
        verify(rebuildService, never()).rebuildInbox(anyLong());
    }

    @Test
    void handleIgnoresUnknownRoutingKey() {
        // feed.rebuild.queue 以 feed.rebuild.# 通配绑定：将来新增子类型会一并投到本队列 → 不误解析、不投死信
        assertDoesNotThrow(() -> consumer.handle("feed.rebuild.other", body(7L)));

        verify(rebuildService, never()).rebuildInbox(anyLong());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    private static byte[] body(long userId) {
        String json = new JacksonCodec().toJson(new InboxRebuildMessage(userId));
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
