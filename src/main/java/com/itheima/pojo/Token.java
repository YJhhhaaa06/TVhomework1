package com.itheima.pojo;

public class Token {
    private long id;
    private String tokenStr;//交给前端的字符串
    private long expireTime;
    private String signature;

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public String getTokenStr() {

        return tokenStr;
    }
    public void setTokenStr(String random) {
        this.tokenStr = random;
    }
    public long getExpireTime() {
        return expireTime;
    }
    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
    }
    public String getSignature() {
        return signature;
    }
    public void setSignature(String signature) {
        this.signature = signature;
    }

    public Token(long id, String tokenStr, long expireTime, String signature) {
        this.id = id;
        this.tokenStr = tokenStr;
        this.expireTime = expireTime;
        this.signature = signature;
    }

    public Token() {
    }

}
