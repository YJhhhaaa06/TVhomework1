package com.itheima.service;

import com.itheima.dao.UserDao;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;

public class UserService {
    //作为通过id登录
    public static void logInAsUserByID(String id,String rawPassword) throws Exception {
        String hashedPassword = UserDao.getPasswordHashByID(id);
        if(hashedPassword==null){
            System.out.println("用户不存在");
            return;
        }
        boolean match= PasswordService.checkPassword(rawPassword,hashedPassword);
        if(match){
            System.out.println("登录成功");
        }
        else {
            System.out.println("登录失败");
        }
    }
    //通过手机号登录
    public static void logInAsUserByPhone(String phone,String rawPassword) throws Exception {
        String hashedPassword = UserDao.getPasswordHashByPhone(phone);
        if(hashedPassword==null){
            System.out.println("用户不存在");
            return;
        }
        boolean match = PasswordService.checkPassword(rawPassword, hashedPassword);
        if(match){
            System.out.println("登录成功");
        }
        else {
            System.out.println("登录失败");
        }
    }
    //用户注册
    public static void registerAsUser(String userName,String password,String phone){
        if(registerCheck(userName,password,phone)){
            UserDao.addUser(userName, PasswordUtil.hashPassword(password),phone);
        }
        else {
            System.out.println("注册失败");
        }

    }




    public static boolean registerCheck(String userName,String password,String phone){
        if(password.length()>=16){//密码太长
            return false;
        }
        if(!StringUtil.phoneCheck(phone)){//电话号码不对
            return false;
        }
        if ((userName.length()>=50)){//名字太长
            return false;
        }
        if(!UserDao.isPhoneUsed(phone)){//电话号码被用了
            return false;
        }
        return true;
    }
}
