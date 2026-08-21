package com.itheima.ioc;

/**
 * Bean 销毁生命周期接口：容器关闭时调用 destroy()。
 */
public interface Disposable {
    void destroy();
}
