package com.itheima.Util;
import org.springframework.security.crypto.bcrypt.BCrypt;
public class PasswordUtil {
    public static String token(){
        return "1";
    }

    public static String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    public static boolean checkPassword(String rawPassword, String hashedPassword) {
        return BCrypt.checkpw(rawPassword, hashedPassword);
    }

}
