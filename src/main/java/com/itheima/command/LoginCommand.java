package com.itheima.command;

public class LoginCommand {
    String phone;
    long id;
    LoginType type;
    String password;

    public LoginCommand(String phone, String password) {
        this.phone = phone;
        this.type = LoginType.BY_PHONE;
        this.password = password;
    }

    public LoginCommand(long id, String password) {
        this.id =id;
        this.type =LoginType.BY_ID;
        this.password = password;
    }

    public static LoginCommand byId(long id,String password){
        return new LoginCommand(id,password);
    }
    public static LoginCommand byPhone(String phone,String password){
        return new LoginCommand(phone,password);
    }

    public LoginCommand() {
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public LoginType getType() {
        return type;
    }

    public void setType(LoginType type) {
        this.type = type;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

