package com.itheima.response;

public class LogInResponse {

    private String token;
    private String username;
    private long id;

    public LogInResponse() {
    }
    public LogInResponse(long id,String username, String token) {
        this.id=id;
        this.token = token;
        this.username=username;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
