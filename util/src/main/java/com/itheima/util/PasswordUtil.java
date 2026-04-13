package com.itheima.util;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.regex.Pattern;

public class PasswordUtil {
    private static final Pattern PWD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,16}$");
    public static String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    //密码是否一致
    public static boolean isPasswordCorrect(String rawPassword, String hashedPassword) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        return encoder.matches(rawPassword, hashedPassword);
    }
    //
    public static boolean isPasswordLegal(String password){
        if (password == null) {
            return false;
        }
        return PWD_PATTERN.matcher(password).matches();
    }



}
