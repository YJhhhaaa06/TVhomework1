package com.itheima.controller;

import com.itheima.util.StringUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.Scanner;

public class MenuController {
    public static boolean signInMenu() throws Exception {//退出返回true
        System.out.println("===========================\n" +
                "      赤石平台\n" +
                "===========================\n" +
                "1. 登录\n" +
                "2. 注册\n" +
                "3. 以游客身份浏览\n" +
                "4. 退出\n"+
                "请选择操作（输入 1-4）：");
        return chooseInMenu();
    }

    public static boolean chooseInMenu() throws Exception {//如果要退出菜单，返回true
        Scanner sc = new Scanner(System.in);
        String str = sc.next();
        if (StringUtil.isAllDigit(str)) {
            int choose = Integer.parseInt(str);
            if (choose >= 1 && choose <= 3) {
                switch (choose) {
                    case 1:
//                        return MenuController.MenuLogIn();
                    case 2:
//                        return MenuController.MenuLogIn();
                    case 3:
                        System.out.println("退出");
                        return true;
                }
            } else {
                System.out.println("输入错误，请重新输入");
                return false;
            }
        } else {
            System.out.println("输入错误，请重新输入");
            return false;
        }
        return false;
    }


}
