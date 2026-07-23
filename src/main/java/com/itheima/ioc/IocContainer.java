package com.itheima.ioc;

import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.ioc.annotation.PostConstruct;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IocContainer {

    private static final IocContainer INSTANCE = new IocContainer();

    private final Map<Class<?>, Object> beans = new ConcurrentHashMap<>();
    private boolean initialized;

    private IocContainer() {}

    public static IocContainer getInstance() { return INSTANCE; }

    // ==================== 初始化 ====================

    public synchronized void init() {
        if (initialized) return;

        List<Class<?>> componentClasses = ClassScanner.scan("com.itheima");
        List<Class<?>> sorted = topologicalSort(componentClasses);

        for (Class<?> clazz : sorted) {
            beans.put(clazz, createInstance(clazz));
        }

        for (Object bean : beans.values()) {
            injectFields(bean);
        }

        for (Object bean : beans.values()) {
            invokePostConstruct(bean);
        }

        initialized = true;
    }

    // ==================== 获取 Bean ====================

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> clazz) {
        return (T) beans.get(clazz);
    }

    // ==================== 外部注入（Controller 用）====================

    public void injectInto(Object target) {
        injectFields(target);
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        for (Object bean : beans.values()) {
            try {
                Method m = bean.getClass().getMethod("shutdown");
                m.invoke(bean);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ==================== 内部实现 ====================

    private Object createInstance(Class<?> clazz) {
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("无法创建实例: " + clazz.getName(), e);
        }
    }

    private void injectFields(Object target) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    Object dependency = beans.get(field.getType());
                    if (dependency != null) {
                        field.setAccessible(true);
                        try {
                            field.set(target, dependency);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("依赖注入失败: "
                                    + target.getClass().getSimpleName() + "."
                                    + field.getName(), e);
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void invokePostConstruct(Object target) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostConstruct.class)
                    && method.getParameterCount() == 0) {
                method.setAccessible(true);
                try {
                    method.invoke(target);
                } catch (Exception e) {
                    throw new RuntimeException("PostConstruct 执行失败: "
                            + target.getClass().getSimpleName() + "."
                            + method.getName(), e);
                }
            }
        }
    }

    // ==================== 拓扑排序 ====================

    private List<Class<?>> topologicalSort(List<Class<?>> allClasses) {
        // dependsOn[A] = A 依赖的类集合
        Map<Class<?>, Set<Class<?>>> dependsOn = new HashMap<>();
        // dependedBy[A] = 依赖 A 的类集合（反向边）
        Map<Class<?>, Set<Class<?>>> dependedBy = new HashMap<>();

        for (Class<?> clazz : allClasses) {
            dependsOn.put(clazz, collectDependencies(clazz, allClasses));
            dependedBy.put(clazz, new HashSet<>());
        }

        for (Class<?> clazz : allClasses) {
            for (Class<?> dep : dependsOn.get(clazz)) {
                dependedBy.get(dep).add(clazz);
            }
        }

        // inDegree = 还有多少依赖未就绪
        Map<Class<?>, Integer> inDegree = new HashMap<>();
        for (Class<?> clazz : allClasses) {
            inDegree.put(clazz, dependsOn.get(clazz).size());
        }

        // 无依赖的类（DAO、LikeCacheService 等）先构建
        Queue<Class<?>> queue = new LinkedList<>();
        for (Map.Entry<Class<?>, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<Class<?>> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Class<?> clazz = queue.poll();
            sorted.add(clazz);
            // clazz 已就绪，依赖它的类减少一个等待计数
            for (Class<?> dependent : dependedBy.get(clazz)) {
                int newDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, newDegree);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (sorted.size() != allClasses.size()) {
            throw new RuntimeException("IOC 容器检测到循环依赖");
        }

        return sorted;
    }

    private Set<Class<?>> collectDependencies(Class<?> clazz, List<Class<?>> allClasses) {
        Set<Class<?>> result = new HashSet<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Inject.class)) {
                Class<?> type = field.getType();
                if (allClasses.contains(type)) {
                    result.add(type);
                }
            }
        }
        return result;
    }
}