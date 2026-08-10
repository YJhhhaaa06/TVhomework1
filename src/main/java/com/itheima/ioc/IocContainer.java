package com.itheima.ioc;

import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.ioc.annotation.PostConstruct;
import com.itheima.util.LogUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IocContainer {

    private static final Logger LOGGER = LogUtil.getLogger(IocContainer.class);
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

        for (Object bean : beans.values()) {
            invokeInitializable(bean);
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
            if (bean instanceof Disposable disposable) {
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Bean destroy 失败: " + bean.getClass().getName(), e);
                }
                continue;
            }
            try {
                Method m = bean.getClass().getMethod("shutdown");
                m.invoke(bean);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Bean shutdown 失败: " + bean.getClass().getName(), e);
            }
        }
    }

    // ==================== 内部实现 ====================

    private Object createInstance(Class<?> clazz) {
        Constructor<?> injectCtor = findInjectConstructor(clazz);
        if (injectCtor != null) {
            Class<?>[] paramTypes = injectCtor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                Object dep = beans.get(paramTypes[i]);
                if (dep == null) {
                    throw new RuntimeException("构造器依赖未就绪: "
                            + clazz.getSimpleName() + " -> " + paramTypes[i].getSimpleName());
                }
                args[i] = dep;
            }
            try {
                injectCtor.setAccessible(true);
                return injectCtor.newInstance(args);
            } catch (Exception e) {
                throw new RuntimeException("构造器创建实例失败: " + clazz.getName(), e);
            }
        }
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("无法创建实例: " + clazz.getName(), e);
        }
    }

    private Constructor<?> findInjectConstructor(Class<?> clazz) {
        Constructor<?> found = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(InjectConstructor.class)) {
                if (found != null) {
                    throw new RuntimeException("存在多个 @InjectConstructor 构造器: " + clazz.getName());
                }
                found = ctor;
            }
        }
        return found;
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

    private void invokeInitializable(Object target) {
        if (target instanceof Initializable initializable) {
            try {
                initializable.init();
            } catch (Exception e) {
                throw new RuntimeException("Initializable.init 执行失败: "
                        + target.getClass().getSimpleName(), e);
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
        Constructor<?> injectCtor = findInjectConstructor(clazz);
        if (injectCtor != null) {
            for (Class<?> paramType : injectCtor.getParameterTypes()) {
                if (allClasses.contains(paramType)) {
                    result.add(paramType);
                }
            }
        }
        return result;
    }
}
