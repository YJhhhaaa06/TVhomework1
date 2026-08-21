package com.itheima.ioc;

/**
 * Bean 初始化生命周期接口：依赖注入完成后、容器可用前调用一次 init()。
 */
public interface Initializable {
    void init();
}
