package com.itheima.controller;

import com.itheima.ioc.IocContainer;
import com.itheima.service.UserService;

public class Test3 {
    static void main() throws Exception {
        IocContainer.getInstance().init();
        UserService u = IocContainer.getInstance().getBean(UserService.class);
    }
}
