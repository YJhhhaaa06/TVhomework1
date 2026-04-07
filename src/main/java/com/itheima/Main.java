package com.itheima;

import com.itheima.Dao.LogIn;
import com.itheima.Dao.Register;
import com.itheima.Util.PasswordUtil;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main() throws Exception {
//        Register.addUser("一号员工", PasswordUtil.hashPassword("123456"),"12345678901");
        LogIn.logInAsUser("1","123456");
        LogIn.logInAsUserByPhone("12345678901","123456");
    }
}
