package com.itheima.util;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.regex.Pattern;

//public class PasswordUtil {
//    private static final Pattern PWD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,16}$");
//    public static String hashPassword(String rawPassword) {
//        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
//    }
//
//    //密码是否一致
//    public static boolean isPasswordCorrect(String rawPassword, String hashedPassword) {
//        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
//        return encoder.matches(rawPassword, hashedPassword);
//    }
//    //
//    public static boolean isPasswordLegal(String password){
//        if (password == null) {
//            return false;
//        }
//        return PWD_PATTERN.matcher(password).matches();
//    }
//}

public class PasswordUtil {
    // 1. 抽离正则，提高复用性能
    private static final Pattern PWD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,16}$");

    // 2. 统一使用 Spring 的编码器，设为静态常量
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * 加密密码
     */
    public static String hashPassword(String rawPassword) {
        // 直接使用 encode 方法，它内部会自动生成盐值
        return ENCODER.encode(rawPassword);
    }

    /**
     * 校验密码
     */
    public static boolean isPasswordCorrect(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) {
            return false;
        }
        return ENCODER.matches(rawPassword, hashedPassword);
    }

    /**
     * 校验格式是否合法
     */
    public static boolean isPasswordLegal(String password){
        return password != null && PWD_PATTERN.matcher(password).matches();
    }
}
