package com.itheima.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.pojo.Token;

public class TokenUtil {
    private static final ObjectMapper mapper = new ObjectMapper();

    //将token转为字符串
    public static String tokenToString(Token token){
        try{
            return mapper.writeValueAsString(token);
        }catch (Exception e){
            throw new RuntimeException("TOKEN_ENCODE_ERROR");
        }

    }

    //字符串转token
    public static Token parseToken(String tokenStr) throws JsonProcessingException {
        try{
            return mapper.readValue(tokenStr,Token.class);
        }catch (Exception e){
            throw new RuntimeException("TOKEN_ENCODE_ERROR");
        }
    }

    //查看token是否过期,过期返回true
    public static boolean isTokenExpire(Token token){
        long expire=token.getExpireTime();
        long currentTime=System.currentTimeMillis();
        return currentTime>expire;
    }
}
