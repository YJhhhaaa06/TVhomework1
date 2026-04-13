package com.itheima.service;

import com.itheima.pojo.Token;
import com.itheima.pojo.User;
import com.itheima.util.TokenUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TokenService {
    private static final List<Token> tokenList = new ArrayList<>();

    //增
    //生成token
    public static Token generateToken(long id) {
        long expireTime = System.currentTimeMillis() + 3600_000L;
        Random r=new Random();

        return new Token(id,r.nextInt()%10000, expireTime,"mySignature");//乱签名
    }
    //删
    //根据ID删除Token
    public static void removeTokenById(long id){
        tokenList.removeIf(token -> token.getId() == id);
    }
    //删除所有过期token
    public static void removeAllExpireToken(List<Token> tokenList){
        List<Token> tokenToDelete=new ArrayList<>();
        for (Token temp : tokenList){
            if(TokenUtil.isTokenExpire(temp)){
                tokenToDelete.add(temp);
            }
        }
        for (Token delete : tokenToDelete){
            tokenList.remove(delete);
        }
    }
    //删除指定的过期Token,如果没过期就不删除,删除了返回true,没删除返回false
    public static boolean removeIfExpire(Token token){
        if(TokenUtil.isTokenExpire(token)){
           tokenList.remove(token);
           return true;
        }
        return false;
    }
    //查
    //数组中是否已有该id的token,存在返回true
    public static boolean isTokenExist(long id) {
        for (Token temp : tokenList) {
            if (temp.getId() == id) {//如果找到目标token
                return true;
            }
        }
        return false;
    }
    //从token列表中找token,找不到返回null
    public static Token searchToken(long id) {
        for (Token temp : tokenList) {
            if (temp.getId() == id) {
                return temp;
            }
        }
        return null;
    }
    //验
    //    比较token是否一致
    public static boolean isTokenEqual(Token token1, Token token2) {
        if (!(token1.getId()==token2.getId())){//id不一致
            return false;
        }
        if (!(token1.getRandom()==(token2.getRandom()))) {//随机码不一致
            return false;
        }
        if (!(token1.getSignature().equals(token2.getSignature()))) {//签发时间不一致
            return false;
        }
        return token1.getExpireTime() == token2.getExpireTime();
    }
    //用户是否和Token匹配
    public static boolean isTokenMatchUser(Token token, User user){
        return token.getId()==user.getId();
    }



    //    高级
    //输入的Token是否有效
    public static boolean isTokenLegal(Token token,User user){
        Token target=null;
        if((target=searchToken(token.getId()))!=null){//如果从列表中找到与输入Token id相同的Token
            if(removeIfExpire(token)){
                //token过期，删除并返回false
                return false;
            }
            if(!isTokenMatchUser(token,user)){
                //如果用户上传了其他人的token
                return false;
            }
            //token在列表中可以找到，而且没过期就返回true
            return isTokenEqual(token, target);
        }
        //找不到与输入token id相同的token
        return false;
    }
    //签发token
    public static Token getNewToken(long id){
        removeTokenById(id);
        Token token=generateToken(id);
        tokenList.add(token);
        return token;
    }



}










