package com.itheima.pojo;

public class Token {
    private long id;
    private int random;
    private long expireTime;
    private String signature;

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public int getRandom() {
        return random;
    }
    public void setRandom(int random) {
        this.random = random;
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

    public Token(long id, int random, long expireTime, String signature) {
        this.id = id;
        this.random = random;
        this.expireTime = expireTime;
        this.signature = signature;
    }

    public Token() {
    }

}
