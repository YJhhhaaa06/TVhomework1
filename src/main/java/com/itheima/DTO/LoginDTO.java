package com.itheima.DTO;

import com.itheima.command.LoginCommand;
import com.itheima.exception.ParamException;
import com.itheima.util.StringUtil;

public class LoginDTO {
    private String account;
    private String password;


    public void setAccount(String account) {
        this.account = account;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public LoginDTO() {
    }
    public String getAccount() {
        return account;
    }
    public String getPassword() {
        return password;
    }

}
