package com.itheima.Service;

import com.itheima.Util.TimeUtil;

import java.util.ArrayList;

public class TokensManage {
    //签发token
    public static Token generateToken(String username, long id) {
        String currentTime = TimeUtil.getCurrentTimestamp();
        return new Token(username, currentTime, id);
    }

    //比较token是否一致
    public static boolean isTokenEqual(Token token1, Token token2) {
//        if (!(token1.getId()==token2.getId())){//id不一致
//            return false;
//        }
        if (!(token1.getUsername().equals(token2.getUsername()))) {//用户名不一致
            return false;
        }
        if (!(token1.getSignTime().equals(token2.getSignTime()))) {//签发时间不一致
            return false;
        }
        if (!(token1.getCheckCode() == token2.getCheckCode())) {
            return false;
        }
        return true;
    }

    //从token数组中找token,找不到返回null
    public static Token searchToken(ArrayList<Token> tokenList, Token target) {
        for (Token temp : tokenList) {
            if (temp.getId() == target.getId()) {
                return temp;
            }
        }
        return null;
    }

    //token是否合法
    public static boolean IsTokenExist(ArrayList<Token> tokenList, Token target) {
        for (Token temp : tokenList) {
            if (isTokenEqual(target, temp)) {//如果找到目标token
                return true;
            }
        }
        return false;
    }


}
