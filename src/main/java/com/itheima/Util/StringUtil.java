package com.itheima.Util;

public class StringUtil {
    public static boolean isAllDigit(String str){//字符串是否全为数字
        if (str == null || str.isEmpty()) {
            return false;
        }
        // 正则：^开头 $结尾，[0-9]+ 一位或多位数字
        return str.matches("^[0-9]+$");
    }
    public static boolean isSpecificLength(int length,String str){
        return str.length()==length;
    }
    public static boolean isLengthLegal(int length,String str){//太长了返回false
        return str.length()<length;
    }
    public static boolean phoneCheck(String phone){
        if (!isAllDigit(phone)){//如果不是全数字
            return false;
        }
        if (phone.length()!=11){//如果长度不对
            return false;
        }
        return phone.charAt(0) == '1';//如果第一位不是1
    }

}
