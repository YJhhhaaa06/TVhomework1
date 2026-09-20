package com.itheima.controller;

import com.itheima.config.AppConfig;
import com.itheima.ioc.IocContainer;
import com.itheima.util.MyConnectionPool;
import com.itheima.util.MyRedisPool;
import com.itheima.util.RequestContext;
import com.itheima.util.LogUtil;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebListener
public class AppShutDownListener implements ServletContextListener {

    private static final Logger LOGGER = LogUtil.getLogger(AppShutDownListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        validateUploadPath(sce);
        // 启动时记录部署上下文，供非请求线程（缓存刷新）拼接媒体 URL
        RequestContext.setDefaultContextPath(sce.getServletContext().getContextPath());
        IocContainer.getInstance().init();
        LOGGER.info("IOC 容器初始化完成");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        IocContainer.getInstance().shutdown();

        MyRedisPool.flushDb();
        MyRedisPool.close();
        MyConnectionPool.closePool();

        LOGGER.info("资源已释放");
    }

    /**
     * 校验 context.xml 的 /upload 静态资源 base 与 app.properties 的 upload.path 一致，
     * 不一致或无法读取时阻止启动。
     */
    private void validateUploadPath(ServletContextEvent sce) {
        String expected = normalizePath(AppConfig.getUploadPath());
        // 防御：upload.path 为空（如 -Dupload.path= 空覆盖）时挂载与落盘都是无效路径，直接拒绝（review 建议）
        if (expected.isEmpty()) {
            throw new IllegalStateException("upload.path 未配置或为空，拒绝启动");
        }
        try (InputStream in = openContextXml(sce)) {
            if (in == null) {
                throw new IllegalStateException("无法读取 META-INF/context.xml，拒绝启动");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            NodeList nodes = doc.getElementsByTagName("PostResources");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String base = el.getAttribute("base");
                if (base == null || base.isEmpty()) {
                    continue;
                }
                // T18：base 可能为 ${upload.path:-<默认>} 占位符（Tomcat 原生展开），先模拟展开再比对，
                // 语义 = "Tomcat 实际挂载 == AppConfig 实际使用"，仍防传 A 目录取 B 目录
                String resolved = normalizePath(resolvePlaceholder(base));
                if (!resolved.equals(expected)) {
                    throw new IllegalStateException(
                            "context.xml 的 /upload base 与 app.properties 不一致: "
                                    + "context.xml=" + base + ", upload.path=" + expected);
                }
                return;
            }
            throw new IllegalStateException("context.xml 中未找到 PostResources base，拒绝启动");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 META-INF/context.xml 失败", e);
        }
    }

    /** ${name:-default} 占位符（name 为属性名，default 可含冒号如 Windows 盘符；默认值缺省）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{(.+?)(?::-([^}]*))?\\}$");

    /**
     * 模拟 Tomcat 对 context.xml 属性值的 ${...} 展开（SystemPropertySource 始终启用）：
     * 系统属性优先，未设置则取占位符内默认值；字面路径原样返回。
     * 注意与 AppConfig 的覆盖链不同——这里只模拟 Tomcat 展开，不查环境变量。
     */
    static String resolvePlaceholder(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        Matcher m = PLACEHOLDER.matcher(text);
        if (!m.matches()) {
            return text;
        }
        String name = m.group(1);
        String def = m.group(2);
        String value = System.getProperty(name);
        return value != null ? value.trim() : (def != null ? def : "");
    }

    private InputStream openContextXml(ServletContextEvent sce) throws Exception {
        InputStream in = sce.getServletContext().getResourceAsStream("/META-INF/context.xml");
        if (in != null) {
            return in;
        }
        String realPath = sce.getServletContext().getRealPath("/META-INF/context.xml");
        if (realPath != null && new File(realPath).exists()) {
            return new FileInputStream(realPath);
        }
        return null;
    }

    private String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
