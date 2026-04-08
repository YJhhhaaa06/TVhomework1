package com.itheima.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtil {
//获取时间戳
    public static String getCurrentTimestamp() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy_MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }

}
