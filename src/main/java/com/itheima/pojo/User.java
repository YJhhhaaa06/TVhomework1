package com.itheima.pojo;

import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;

public class User {
    private long id;
    private String username;
    private String hashedPassword;
    private int followerCount;
    private int followCount;
    private String phone;

    //为注册设计的构造，如果输入不合法就会构造失败
    public User(String username, String phone, String rawPassword) {
        if (username == null || username.trim().isEmpty())
            throw new IllegalArgumentException("用户名不能为空");
        if (phone == null || phone.trim().isEmpty())
            throw new IllegalArgumentException("手机号不能为空");
        if (rawPassword == null || rawPassword.trim().isEmpty())
            throw new IllegalArgumentException("密码不能为空");


        if (!StringUtil.phoneCheck(phone))
            throw new IllegalArgumentException("手机号格式不正确");
        if (!PasswordUtil.isPasswordLegal(rawPassword))  // 只检查长度、字符类型等
            throw new IllegalArgumentException("密码必须为6-16位字母数字组合"); //提示
        if (username.length() > 50)
            throw new IllegalArgumentException("用户名不能超过50个字符");


        this.hashedPassword = PasswordUtil.hashPassword(rawPassword);
        this.phone = phone;
        this.username = username;
    }

    //为登录设计的构造，登录时只需要这些
    public User(long id, String hashedPassword, String userName, String phone) {
        this.id = id;
        this.hashedPassword = hashedPassword;
        this.username = userName;
        this.phone = phone;
    }

    //为查询个人信息设计的构造，
    public User(long id, String username, int followCount, int followerCount) {
        this.id = id;
        this.username = username;
        this.followCount = followCount;
        this.followerCount = followerCount;
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
        this.username = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }


    public String getUserName() {
        return username;
    }

    public void setUserName(String userName) {
        this.username = userName;
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
