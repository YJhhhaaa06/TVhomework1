package com.itheima.Service;

import java.util.Random;

public class Token {
    private String username;
    private long id;
    private String signTime;
    private int checkCode;

    public Token(String username, String signTime, long id) {
        this.username = username;
        this.signTime = signTime;
        this.id = id;
        Random r=new Random();
        this.checkCode=r.nextInt()%10;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSignTime() {
        return signTime;
    }

    public void setSignTime(String signTime) {
        this.signTime = signTime;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getCheckCode() {
        return checkCode;
    }

    @Override
    public String toString(){
        return username+"\n"
                +id+"\n"
                +signTime+"\n"
                +checkCode+"\n";
    }
}
