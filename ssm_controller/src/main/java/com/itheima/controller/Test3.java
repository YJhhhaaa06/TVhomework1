package com.itheima.controller;

import com.itheima.pojo.LogInResult;
import com.itheima.service.UserService;

public class Test3 {
    static void main() throws Exception {
        UserService u=new UserService();
        LogInResult res= u.logInAsUserByPhone("10000000002","123");
        System.out.println(res.getUser().getUserName());
        System.out.println(res.getUser().getId());
    }
}
