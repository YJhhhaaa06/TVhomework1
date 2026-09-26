package com.itheima.mq;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqTopology} 命名唯一源的口径断言（T17 feed1-17）：
 * 一期拓扑一旦被 T18/T19 引用即不易改，故用测试锁死"前缀 / 互不重复 / 死信指向"三条。
 */
class MqTopologyTest {

    @Test
    void allNamesAreFeedPrefixedAndNonBlank() {
        for (String name : allNames()) {
            assertNotNull(name, "拓扑名不应为 null");
            assertTrue(name.startsWith("feed."), "拓扑名应以 feed. 前缀: " + name);
        }
    }

    @Test
    void allNamesAreDistinct() {
        Set<String> seen = new LinkedHashSet<>();
        for (String name : allNames()) {
            assertTrue(seen.add(name), "拓扑名重复: " + name);
        }
    }

    @Test
    void queuesBindToTheirExchangeWithWildcardPattern() {
        assertEquals("feed.push.#", MqTopology.BIND_PUSH_ALL);
        assertEquals("feed.rebuild.#", MqTopology.BIND_REBUILD_ALL);
    }

    @Test
    void deadLetterArgsRouteToDlxAndDlq() {
        Map<String, Object> args = MqTopology.deadLetterArgs();

        assertEquals(MqTopology.EXCHANGE_DLX, args.get("x-dead-letter-exchange"));
        assertEquals(MqTopology.RK_DLQ, args.get("x-dead-letter-routing-key"));
    }

    private static Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(MqTopology.EXCHANGE_PUSH);
        names.add(MqTopology.EXCHANGE_REBUILD);
        names.add(MqTopology.EXCHANGE_DLX);
        names.add(MqTopology.QUEUE_PUSH);
        names.add(MqTopology.QUEUE_REBUILD);
        names.add(MqTopology.QUEUE_DLQ);
        names.add(MqTopology.RK_PUSH_CONTENT);
        names.add(MqTopology.RK_REBUILD_INBOX);
        names.add(MqTopology.RK_DLQ);
        names.add(MqTopology.BIND_PUSH_ALL);
        names.add(MqTopology.BIND_REBUILD_ALL);
        return names;
    }
}
