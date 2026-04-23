package com.itheima.pojo;

public class LogInResult {
    private User user;
    private String token;

    public LogInResult() {
    }
    public LogInResult(User user, String token) {
        this.user = user;
        this.token = token;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }


}
