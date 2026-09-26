package com.itheima.mq;

import com.itheima.ioc.annotation.Component;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;

/**
 * 拓扑声明（T17 feed1-17）：幂等声明 3 交换机 + 3 队列 + 3 绑定。
 *
 * <p>每次"建立新连接"后调用一次（见 {@link MqConnectionManager}）。声明全部 <b>durable</b>，
 * 对齐一期"应用重启 flushDb 清 Redis 收件箱 → 靠重建自愈、broker 侧消息不受影响"的可靠性口径。
 *
 * <p>独立成类而非内联进连接管理：单测可对声明做精确断言（mock {@link Channel} 后 verify 调用），
 * 未来加拓扑也只改这一处。
 */
@Component
public class MqTopologyDeclarer {

    /**
     * 声明全部交换机 / 队列 / 绑定。幂等（同名同参重复声明在 broker 侧是 no-op）。
     *
     * @param channel 用于声明的 channel（通常为发布 channel）
     * @throws IOException broker 侧声明失败
     */
    public void declare(Channel channel) throws IOException {
        channel.exchangeDeclare(MqTopology.EXCHANGE_PUSH, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(MqTopology.EXCHANGE_REBUILD, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(MqTopology.EXCHANGE_DLX, BuiltinExchangeType.DIRECT, true);

        channel.queueDeclare(MqTopology.QUEUE_PUSH, true, false, false, MqTopology.deadLetterArgs());
        channel.queueDeclare(MqTopology.QUEUE_REBUILD, true, false, false, MqTopology.deadLetterArgs());
        // DLQ 自身不设死信参数（防环）
        channel.queueDeclare(MqTopology.QUEUE_DLQ, true, false, false, null);

        channel.queueBind(MqTopology.QUEUE_PUSH, MqTopology.EXCHANGE_PUSH, MqTopology.BIND_PUSH_ALL);
        channel.queueBind(MqTopology.QUEUE_REBUILD, MqTopology.EXCHANGE_REBUILD, MqTopology.BIND_REBUILD_ALL);
        channel.queueBind(MqTopology.QUEUE_DLQ, MqTopology.EXCHANGE_DLX, MqTopology.RK_DLQ);
    }
}
