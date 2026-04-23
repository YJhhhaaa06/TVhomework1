package com.itheima.controller;

import com.itheima.service.UserService;

public class Test3 {
    static void main() throws Exception {
        UserService u=new UserService();
        String res= u.logInAsUserByPhone("10000000002","123");

    }
}
