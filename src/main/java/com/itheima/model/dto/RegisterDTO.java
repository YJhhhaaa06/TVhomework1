package com.itheima.model.dto;
import com.itheima.exception.ParamException;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;


import com.itheima.exception.ParamException;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;
public class RegisterDTO {
    private String username;
    private String phone;
    private String password;

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setPassword(String password) {
            this.password = password;
    }

    public RegisterDTO() {
    }

    public String getUsername() {
        return username;
    }

    public String getPhone() {
        return phone;
    }

    public String getPassword() {
        return password;
    }
}
