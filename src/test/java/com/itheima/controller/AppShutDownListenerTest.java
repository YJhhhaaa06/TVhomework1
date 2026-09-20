package com.itheima.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T18（N13）配置卫生：{@link AppShutDownListener#resolvePlaceholder} 纯函数单测
 * （模拟 Tomcat 对 context.xml ${...} 的系统属性展开：sysprop > 占位符默认值；不查环境变量）。
 */
class AppShutDownListenerTest {

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("upload.path");
    }

    @Test
    void placeholderWithDefaultReturnsDefaultWhenPropertyAbsent() {
        String raw = "${upload.path:-D:/data/projects/VideoPlatform/stone}";
        assertEquals("D:/data/projects/VideoPlatform/stone", AppShutDownListener.resolvePlaceholder(raw));
    }

    @Test
    void placeholderReturnsSystemPropertyWhenPresent() {
        System.setProperty("upload.path", "E:/tmp/verify-media");
        String raw = "${upload.path:-D:/data/projects/VideoPlatform/stone}";
        assertEquals("E:/tmp/verify-media", AppShutDownListener.resolvePlaceholder(raw));
    }

    @Test
    void placeholderWithoutDefaultAndWithoutPropertyReturnsEmpty() {
        assertEquals("", AppShutDownListener.resolvePlaceholder("${upload.path}"));
    }

    @Test
    void literalPathReturnsAsIs() {
        assertEquals("D:/data/stone", AppShutDownListener.resolvePlaceholder("D:/data/stone"));
    }

    @Test
    void nullReturnsEmpty() {
        assertEquals("", AppShutDownListener.resolvePlaceholder(null));
    }

    @Test
    void surroundingWhitespaceTrimmed() {
        assertEquals("D:/data/stone", AppShutDownListener.resolvePlaceholder("  D:/data/stone  "));
        assertEquals("D:/data/projects/VideoPlatform/stone",
                AppShutDownListener.resolvePlaceholder("  ${upload.path:-D:/data/projects/VideoPlatform/stone}  "));
    }
}
