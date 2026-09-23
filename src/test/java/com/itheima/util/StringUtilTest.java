package com.itheima.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * T9（log2-09）统一脱敏出口单测：{@link StringUtil#maskForLog(String, String)} 的
 * **fail-closed** 契约（未登记字段名 / null / 非 11 位 → 一律掩码，绝不回显原值）。
 *
 * <p>不碰 DB / Redis / HTTP；纯函数断言（C1）——"真实日志行里是否出现明文手机号"属落盘断言，
 * 归 pytest（{@code src/test/python/test_milestone_log.py} 的全文件不变式）。
 */
class StringUtilTest {

    private static final String PHONE = "13812345678";

    @Test
    void maskForLogMasksRegisteredPhoneField() {
        assertEquals("138****5678", StringUtil.maskForLog("phone", PHONE),
                "已登记字段按 maskPhone 口径脱敏（首 3 + **** + 末 4）");
        assertEquals("138****5678", StringUtil.maskForLog("PHONE", PHONE),
                "字段名大小写不敏感（调用点写 PHONE/Phone 不应退化成整串掩码）");
    }

    @Test
    void maskForLogNeverReturnsRawValueForUnregisteredField() {
        assertEquals("******", StringUtil.maskForLog("email", "a@b.com"),
                "未登记字段名一律掩码（fail-closed）：名字写错只会多打码、不会漏打码");
        assertEquals("******", StringUtil.maskForLog(null, PHONE),
                "字段名为 null 同样掩码，不得回显原值");
    }

    @Test
    void maskForLogMasksNullAndMalformedValues() {
        assertEquals("******", StringUtil.maskForLog("phone", null));
        assertEquals("******", StringUtil.maskForLog("phone", ""), "空值不可能是合法手机号 → 掩码");
        assertEquals("******", StringUtil.maskForLog("phone", "1381234567"), "非 11 位 → 掩码");
        assertEquals("******", StringUtil.maskForLog("phone", "abcdefghijk"),
                "**长度恰为 11 但不是手机号**（非全数字）→ 整串掩码：位段掩码只对形态合法的号码开，"
                        + "否则 fail-closed 只是措辞（评审 🟡-2 的边界）");
        assertEquals("******", StringUtil.maskForLog("phone", "23812345678"),
                "11 位全数字但首位非 1 同样整串掩码（判据 = phoneCheck）");
    }

    @Test
    void maskedResultCarriesNoFullPhoneNumber() {
        String masked = StringUtil.maskForLog("phone", PHONE);
        assertFalse(masked.contains(PHONE), "脱敏结果不得包含完整手机号");
        assertFalse(masked.matches(".*1[3-9]\\d{9}.*"), "脱敏结果不得含 11 位连续数字（pytest 侧同一判据）");
    }
}
