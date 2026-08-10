package com.itheima.util;

import com.itheima.config.AppConfig;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Date;

public class JwtUtil {
    private static final String SECRET = AppConfig.getJwtSecret(); //用于生成签名
    private static final long EXPIRE_TIME = AppConfig.getJwtExpireMillis();

    private static final Algorithm algorithm = Algorithm.HMAC256(SECRET);
    // 生成 token
    public static String generateToken(Long userId) {return JWT.create()
            .withSubject(userId.toString())
            .withIssuedAt(new Date())
            .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_TIME))
            .sign(algorithm);
    }
    // 校验 token（会自动校验 exp）
    public static DecodedJWT verifyToken(String token) {return JWT.require(algorithm)
            .build()
            .verify(token);
    }
    // 获取 userId
    public static Long getUserId(String token) {DecodedJWT jwt = verifyToken(token);return Long.parseLong(jwt.getSubject());
    }

    public static boolean isTokenValid(String token){
        try{
            verifyToken(token);
            return true;
        }catch (JWTVerificationException e){
            return false;
        }
    }
}
