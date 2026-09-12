package com.itheima.cache;

import com.itheima.ioc.annotation.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 统一单飞组件（NEEDS 4.9）：进程内锁 + 双检，同一 key 的并发 miss 只触发一次 loader，
 * 由内容/评论/点赞/空标记等全部回填点复用，不各自实现。
 *
 * <p>三个配套约束：
 * <ol>
 *   <li>loader 失败必须 remove，防止缓存失败结果（后续请求重复执行全新 loader）；</li>
 *   <li>成功后清理 inFlight 项，防 key 无限增长内存泄漏（NEEDS 4.9 约束 ②）；</li>
 *   <li>进程内锁只对单实例有效，多实例需分布式锁（当前单 Tomcat 够用，不过度设计）。</li>
 * </ol>
 *
 * <p>单飞不改变缓存语义，只解决"同一瞬间重复打 DB"，与 TTL、空标记、失效策略正交。
 */
@Component
public class SingleFlight {

    private final ConcurrentHashMap<String, FutureTask<?>> inFlight = new ConcurrentHashMap<>();

    /**
     * 执行单飞加载：同 key 并发只执行一次 {@code loader}，其余线程等待同一结果。
     *
     * <p>成功与失败都会从 inFlight 移除；失败时对首个等待线程抛 {@link RuntimeException}，
     * 其余线程拿到相同的完成结果（失败）后同样移除。
     *
     * @param key    单飞 key（通常=缓存数据 key）
     * @param loader 实际加载动作（不可返回 null 语义由调用方定义）
     * @return loader 结果
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Callable<T> loader) {
        FutureTask<T> task = new FutureTask<>(loader);
        FutureTask<?> existing = inFlight.putIfAbsent(key, task);
        if (existing != null) {
            task = (FutureTask<T>) existing;
        } else {
            task.run();
        }
        try {
            T result = task.get();
            // 成功后清理，防 key 无限增长（4.9 约束 ②）
            inFlight.remove(key, task);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.remove(key, task);
            throw new RuntimeException("单飞加载被中断, key=" + key, e);
        } catch (ExecutionException e) {
            // loader 失败必须 remove，防缓存失败结果（4.9 约束 ①）
            inFlight.remove(key, task);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("单飞加载失败, key=" + key, cause);
        }
    }

    /** 当前在飞任务数（防御性观测用途）。 */
    public int inFlightCount() {
        return inFlight.size();
    }
}