package com.slz.crm.common.untils;

import java.util.regex.Pattern;

/**
 * 格式验证工具类，提供电话、手机的规范化判断
 */
public final class ValidationUtils {
    // 私有构造方法，防止实例化
    private ValidationUtils() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    // 手机号码正则表达式：以1开头，11位数字
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    // 固定电话正则表达式：区号可选，包含区号时格式为0XX-XXXXXXX或(0XX)XXXXXXX，不含区号时为7-8位数字
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\(0\\d{2}\\)|0\\d{2}-)?[1-9]\\d{6,7}$");

    /**
     * 判断手机号码是否规范
     * @param mobile 手机号码
     * @return true-规范，false-不规范
     */
    public static boolean isValidMobile(String mobile) {
        if (mobile == null || mobile.isEmpty()) {
            return false;
        }
        return MOBILE_PATTERN.matcher(mobile).matches();
    }

    /**
     * 判断固定电话是否规范
     * @param phone 固定电话
     * @return true-规范，false-不规范
     */
    public static boolean isValidPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        return PHONE_PATTERN.matcher(phone).matches();
    }

    public static boolean isValidEmail(String email) {
        return email != null && !email.isEmpty();
    }
}
