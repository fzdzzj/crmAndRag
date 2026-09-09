package com.slz.crm.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 备注类型枚举
 */
@Getter
@AllArgsConstructor
public enum RemarkType {

    HOBBY(1, "喜好"),
    ADDRESS(2, "住址"),
    SELF_BIRTHDAY(3, "本人出生日期"),
    RELATIVE_BIRTHDAY(4, "亲属出生日期"),
    CUSTOM(5, "自定义");

    private final int code;
    private final String desc;

    /**
     * 根据 code 获取枚举
     */
    public static RemarkType getByCode(int code) {
        return Arrays.stream(values())
                .filter(r -> r.code == code)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取类型描述
     */
    public static String getDescByCode(int code) {
        RemarkType type = getByCode(code);
        return type != null ? type.desc : "未知";
    }

    /**
     * 判断是否需要填写备注内容
     */
    public static boolean needContent(int code) {
        return code == 1 || code == 2 || code == 5;
    }

    /**
     * 判断是否需要填写出生日期
     */
    public static boolean needBirthday(int code) {
        return code == 3 || code == 4;
    }

    /**
     * 判断是否需要填写姓名
     */
    public static boolean needName(int code) {
        return code == RELATIVE_BIRTHDAY.getCode();
    }

    /**
     * 判断是否只允许填写出生日期（本人出生日期）
     * 这类类型不能填写备注内容和姓名
     */
    public static boolean isSelfBirthday(int code) {
        return code == SELF_BIRTHDAY.getCode();
    }

    /**
     * 判断是否只允许填写出生日期和姓名（亲属出生日期）
     * 这类类型不能填写备注内容
     */
    public static boolean isRelativeBirthday(int code) {
        return code == RELATIVE_BIRTHDAY.getCode();
    }

    /**
     * 判断是否只允许填写备注内容（喜好、住址、自定义）
     * 这类类型不能填写姓名和出生日期
     */
    public static boolean onlyNeedContent(int code) {
        return code == HOBBY.getCode() || code == ADDRESS.getCode() || code == CUSTOM.getCode();
    }

    /**
     * 判断是否只允许填写出生日期（本人出生日期、亲属出生日期）
     * 这类类型不能填写备注内容
     */
    public static boolean onlyNeedBirthday(int code) {
        return code == SELF_BIRTHDAY.getCode() || code == RELATIVE_BIRTHDAY.getCode();
    }
}
