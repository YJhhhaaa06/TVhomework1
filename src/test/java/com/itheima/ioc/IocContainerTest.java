package com.itheima.ioc;

import com.itheima.ioc.annotation.Inject;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IocContainer 注入解析失败可观测（T17，N12 前半）：
 * 临时造一个未注册依赖，验证被逐条 WARNING + 汇总清单报出；fail-fast 开启时注入点即抛。
 */
class IocContainerTest {

    /** 未注册依赖：只被 HostBean 引用，容器中不注册。 */
    static class MissingDep {
    }

    /** 宿主 Bean：带一个指向未注册类型的 @Inject 字段。 */
    static class HostBean {
        @Inject
        private MissingDep dep;

        MissingDep getDep() {
            return dep;
        }
    }

    private final List<LogRecord> warningRecords = new ArrayList<>();
    private Handler collector;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = LogUtil.getLogger(IocContainer.class);
        collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warningRecords.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(collector);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(collector);
    }

    private List<String> warningMessages() {
        List<String> msgs = new ArrayList<>();
        for (LogRecord r : warningRecords) {
            msgs.add(r.getMessage());
        }
        return msgs;
    }

    @Test
    void missingDependencyFailsFastWhenEnabled() {
        IocContainer container = IocContainer.createForTest(true);
        HostBean host = new HostBean();
        container.registerForTest(HostBean.class, host);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> container.injectInto(host));

        assertTrue(ex.getMessage().contains("fail-fast"));
        assertTrue(ex.getMessage().contains(HostBean.class.getName() + ".dep"));
        List<String> msgs = warningMessages();
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("IoC 注入解析失败")));
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("IoC 未注入清单汇总")));
        assertNull(host.getDep());
    }

    @Test
    void missingDependencyOnlyWarnsWhenFailFastDisabled() {
        IocContainer container = IocContainer.createForTest(false);
        HostBean host = new HostBean();
        container.registerForTest(HostBean.class, host);

        assertDoesNotThrow(() -> container.injectInto(host));

        List<String> msgs = warningMessages();
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("IoC 注入解析失败")));
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("IoC 未注入清单汇总")));
        assertNull(host.getDep());
    }

    @Test
    void injectSucceedsWhenDependencyRegistered() {
        IocContainer container = IocContainer.createForTest(false);
        HostBean host = new HostBean();
        MissingDep dep = new MissingDep();
        container.registerForTest(HostBean.class, host);
        container.registerForTest(MissingDep.class, dep);

        assertDoesNotThrow(() -> container.injectInto(host));

        assertSame(dep, host.getDep());
        assertTrue(warningMessages().isEmpty());
    }
}
