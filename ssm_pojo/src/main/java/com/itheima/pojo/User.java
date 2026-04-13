package com.itheima.pojo;

public class User {
    private long id;
    private String userName;
    private String hashedPassword;
    private int followerCount;
    private int followCount;
    private String token;
    private String phone;


    public User(long id, String userName, String hashedPassword, int followerCount, int followCount, String phone) {
        this.id = id;
        this.userName = userName;
        this.hashedPassword = hashedPassword;
        this.followerCount = followerCount;
        this.followCount = followCount;
        this.token = token;
        this.phone = phone;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public User() {
    }



    public void setName(String name) {
        this.userName = name;
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

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getHashedPassword() {
        return hashedPassword;
    }

    public void setHashedPassword(String hashedPassword) {
        this.hashedPassword = hashedPassword;
    }
    public void clear() {
        this.hashedPassword = null;
        this.phone=null;
    }
    public int getFollowerCount() {
        return followerCount;
    }
    public void setFollowerCount(int followerCount) {
        this.followerCount = followerCount;
    }
    public int getFollowCount() {
        return followCount;
    }
    public void setFollowCount(int followCount) {
        this.followCount = followCount;
    }
}
