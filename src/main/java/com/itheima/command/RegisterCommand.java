package com.itheima.command;

public class RegisterCommand {
    String phone;
    String username;
    String password;

    public RegisterCommand() {
    }

    public RegisterCommand(String phone, String username, String password) {
        this.phone = phone;
        this.username = username;
        this.password = password;
    }

    public static RegisterCommand getInstance(String phone,String password,String username){
        return new RegisterCommand(phone,username,password);
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
