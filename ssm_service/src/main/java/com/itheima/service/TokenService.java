package com.itheima.service;

import com.itheima.pojo.Token;
import com.itheima.pojo.User;
import com.itheima.pojo.Video;
import com.itheima.util.TokenUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TokenService {

    private static final Map<String, Token> tokenMap = new ConcurrentHashMap<>();
    private static final Map<Long, Token> idMap = new ConcurrentHashMap<>();

    public void startScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // 参数：任务, 首次执行延迟, 连续执行间隔, 时间单位
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                e.printStackTrace(); // 防止异常导致调度终止
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            System.err.println("初始化视频缓存失败！");
            e.printStackTrace();
            throw e;
        }         //启动时加载一次
        startScheduler();   //开启定时刷新
    }

    public void refresh() {
        Iterator<Map.Entry<String, Token>> iterator = tokenMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Token> entry = iterator.next();
            Token token = entry.getValue();

            if (TokenUtil.isTokenExpire(token)) {
                iterator.remove(); // 使用迭代器的 remove 方法，安全地删除当前元素
                idMap.remove(token.getId());//idMap也同步删除
            }
        }
    }


        //增
        //生成token
        public  String generateToken ( long id){
            String tokenStr = UUID.randomUUID().toString();//生成一个几乎不会重复的随机字符串Universally Unique Identifier
            long expireTime = System.currentTimeMillis() + 3600_000L;//过期时间，当前时间+3600000毫秒
            Token token = new Token(id, tokenStr, expireTime, "mySignature");//随便签个名
            tokenMap.put(tokenStr, token);
            idMap.put(token.getId(),token);
            return tokenStr;
        }
        //删
        //根据tokenStr删除Token
        public  void removeTokenById (long id){
            Token token;
            if ((token=idMap.get(id))!=null){
                tokenMap.remove(token.getTokenStr());//如果存在才会删
                idMap.remove(id);
            }
        }

        //删除所有过期token
        public  void removeAllExpireToken () {
            // 1. 获取 entrySet 的迭代器
            Iterator<Map.Entry<String,Token>> it = tokenMap.entrySet().iterator();
            //循环判断是否有下一个
            while (it.hasNext()) {
                // 3. 取出当前的键值对
                Map.Entry<String, Token> entry = it.next();
                com.itheima.pojo.Token token = entry.getValue();//取出token
                // 4. 根据逻辑判断是否删除
                if (TokenUtil.isTokenExpire(token)) {
                    it.remove(); // 重点：必须调用 it.remove()，不能用 tokenMap.remove()
                    //不能连续使用，要用next让下一个元素出列，才能再次remove
                    idMap.remove(token.getId());
                }
            }


        }
        //删除指定的过期Token,如果没过期就不删除,删除了返回true,没删除返回false
        public  boolean removeIfExpire (Token token){
            if (TokenUtil.isTokenExpire(token)) {
                tokenMap.remove(token.getTokenStr());
                return true;
            }
            return false;
        }
        //查
        //map中是否已有该id的token,存在返回true
        public  boolean isTokenExist ( long id){
            return idMap.get(id) != null;
        }
        //从token列表中根据id找token,找不到返回null
        public  Token searchToken ( long id){
//            // 1. 获取 entrySet 的迭代器
//            Iterator<Map.Entry<String,Token>> it = tokenMap.entrySet().iterator();
//            //循环判断是否有下一个
//            while (it.hasNext()) {
//                // 3. 取出当前的键值对
//                Map.Entry<String, Token> entry = it.next();
//                com.itheima.pojo.Token token = entry.getValue();//取出token
//                // 4. 如果找到对应Id的token就返回
//                if (token.getId()==id) {
//                    it.remove(); // 重点：必须调用 it.remove()，不能用 tokenMap.remove()
//                    //不能连续使用，要用next让下一个元素出列，才能再次remove
//                }
//            }
            return idMap.get(id);
        }
        public  Long getUserId (String tokenStr){
            com.itheima.pojo.Token token =tokenMap.get(tokenStr);
            return token.getId();
        }

        //验
        //    比较token是否一致
//        public static boolean isTokenEqual (Token token1, Token token2){
//            if (!(token1.getId() == token2.getId())) {//id不一致
//                return false;
//            }
//            if (!(token1.getTokenStr().equals(token2.getTokenStr()))) {//随机码不一致
//                return false;
//            }
//            if (!(token1.getSignature().equals(token2.getSignature()))) {//签发时间不一致
//                return false;
//            }
//            return token1.getExpireTime() == token2.getExpireTime();
//        }
        //用户是否和Token匹配
        public  boolean isTokenMatchUser (Token token, User user){
            return token.getId() == user.getId();
        }


        //    高级
        //输入的Token是否有效
        public  boolean isTokenLegal (String tokenStr){
        Token token;
            if((token=tokenMap.get(tokenStr))==null){//map没找到token
                return false;
            }
            //如果token过期了返回false（顺便删除）
            return !removeIfExpire(token);
        }
        //签发token
        public  String getNewToken ( long id){
            removeTokenById(id);
            return generateToken(id);
        }


}










