package com.itheima.mq;

import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 发布封装（T17 feed1-17）：publisher confirm 模式，**任何情况下都不抛异常**（只记日志并返回 false）。
 *
 * <p><b>线程安全（一期拍板，2026-09-26）</b>：{@code Channel} 非线程安全，且 {@code waitForConfirms}
 * 依赖 channel 上"当前在途发布序号"来做确认关联——多线程并发发布到同一 channel 会让确认语义不可判定。
 * 故采用<b>单条 confirm channel + {@code synchronized} 串行「publish + waitForConfirms」</b>，
 * 每条消息得到一个确定结论。一期发布量低（仅内容发布后投递），串行开销可忽略；
 * 每线程 channel / 异步批量确认按三期吞吐议题再评估。
 *
 * <p><b>降级</b>：连接不可用时直接返回 false，<b>不访问 broker、不记日志</b>（避免每个请求刷日志）；
 * 真正尝试后的失败才记 WARNING（含 nack / 确认超时 / IO 异常 / 线程中断）——这些是各自失败链的
 * <b>唯一捕获点</b>，故一律持栈（`LOG_CONVENTION` §3.1 附加纪律 2）；nack 无异常对象，属结论行、无栈可持。
 */
@Component
public class MqPublisher {

    private static final Logger LOGGER = LogUtil.getLogger(MqPublisher.class);

    /** 发布确认等待超时（三期可提配置）。 */
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000L;

    /** 持久化 + JSON 消息属性（deliveryMode=2 对齐一期"broker 侧消息不受应用重启影响"口径）。 */
    private static final AMQP.BasicProperties PERSISTENT_JSON = new AMQP.BasicProperties.Builder()
            .deliveryMode(2)
            .contentType("application/json")
            .build();

    private final MqConnectionProvider provider;

    /** confirm 串行锁（见类注释"线程安全"）。 */
    private final Object confirmLock = new Object();

    /** IoC 注入构造：形参必须为具体类（IoC 按具体类解析依赖）。 */
    @InjectConstructor
    public MqPublisher(MqConnectionManager connectionManager) {
        this.provider = connectionManager;
    }

    /** 包级可见：供单测注入 mock 连接提供者，无需真实 broker。 */
    MqPublisher(MqConnectionProvider provider) {
        this.provider = provider;
    }

    /**
     * 发布一条消息并等待 broker 确认。
     *
     * @return true = 已确认；false = 降级 / 未确认 / 失败（调用方无需处理异常，失败不影响业务）
     */
    public boolean publish(MqMessage message) {
        if (message == null || message.body() == null) {
            return false;
        }
        if (!provider.ensureConnected()) {
            return false;
        }
        Channel channel = provider.publisherChannel();
        if (channel == null) {
            return false;
        }
        synchronized (confirmLock) {
            try {
                channel.basicPublish(message.exchange(), message.routingKey(),
                        PERSISTENT_JSON, message.body());
                boolean confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MILLIS);
                if (!confirmed) {
                    LOGGER.log(Level.WARNING, "MQ 发布未被确认（nack）: exchange=" + message.exchange()
                            + ", routingKey=" + message.routingKey());
                }
                return confirmed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "MQ 发布确认被中断: routingKey=" + message.routingKey(), e);
                return false;
            } catch (TimeoutException e) {
                LOGGER.log(Level.WARNING, "MQ 发布确认超时: routingKey=" + message.routingKey(), e);
                return false;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "MQ 发布失败（已降级，不影响业务）: routingKey="
                        + message.routingKey(), e);
                return false;
            }
        }
    }
}
