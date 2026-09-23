package com.itheima.util;

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
    public static boolean isLengthLegal(int maxLength,String str){//太长了返回false
        return str.length()<maxLength;
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

    /**
     * 手机号脱敏：138****1234。非 11 位或为 null 时统一返回 ******。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return "******";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * **日志脱敏的唯一出口**（T9 立，口径见 `说明书/LOG_CONVENTION.md` §3.7）：
     * 任何"想写进日志的敏感字段"都必须经过本方法取脱敏值，调用点不得自行截串/拼接。
     *
     * <p>语义（**fail-closed**）：只识别已登记的字段类型 + **形态合法**的值，
     * **其余一切输入（未登记字段名 / null / 空值 / 形态不符）一律返回掩码 {@code ******}**
     * ——字段名写错或值形态异常只会"多打码"，不会"漏打码"。
     * 即 {@code phone} 只在通过 {@link #phoneCheck(String)}（11 位全数字且首位为 1）时按
     * {@link #maskPhone(String)} 口径保留首 3 + 末 4，其余一律整串掩码。
     *
     * <p>当前登记：{@code phone}。新增字段类型 = 在本方法内加一条分派、并在 §3.7 登记
     * （`说明书/LOG_CONVENTION.md`），不另建类/不另立第二出口。
     *
     * @param field 字段类型名（当前仅 {@code phone}，大小写不敏感）
     * @param value 原始值（**绝不允许先把原值拼进日志再脱敏**）
     * @return 可直接落盘的脱敏值
     */
    public static String maskForLog(String field, String value) {
        if ("phone".equalsIgnoreCase(field) && phoneCheck(value)) {
            return maskPhone(value);
        }
        return "******";
    }

}
