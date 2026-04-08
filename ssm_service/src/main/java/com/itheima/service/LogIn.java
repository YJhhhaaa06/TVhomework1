package com.itheima.service;

import com.itheima.dao.SearchInfo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class LogIn {
    public static void logInAsUser(String id,String rawPassword) throws Exception {
        String hashedPassword = SearchInfo.getPasswordHashByID(id);
        if(hashedPassword==null){
            System.out.println("用户不存在");
            return;
        }
        boolean match= PasswordManage.checkPassword(rawPassword,hashedPassword);
        if(match){
            System.out.println("登录成功");
        }
        else {
            System.out.println("登录失败");
        }
    }
    public static void logInAsUserByPhone(String phone,String rawPassword) throws Exception {
        String hashedPassword = SearchInfo.getPasswordHashByPhone(phone);
        if(hashedPassword==null){
            System.out.println("用户不存在");
            return;
        }
        boolean match = PasswordManage.checkPassword(rawPassword, hashedPassword);
        if(match){
            System.out.println("登录成功");
        }
        else {
            System.out.println("登录失败");
        }
    }
}
