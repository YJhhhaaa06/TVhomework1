package com.itheima.util;

import java.util.HashMap;
import java.util.Map;

public class ResultUtil {
    public static Map<String, Object> success(Object data) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", 200);
        map.put("msg", "success");
        map.put("data", data);
        return map;
    }
    public static Map<String, Object> success(Object data,String username) {
        Map<String, Object> map = success(data);
        map.put("username",username);
        return map;
    }

    public static Map<String, Object> error(int code, String msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("msg", msg);
        return map;
    }
}
