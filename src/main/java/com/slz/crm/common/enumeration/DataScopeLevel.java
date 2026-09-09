package com.slz.crm.common.enumeration;

import lombok.Getter;

/**
 * 数据范围级别枚举
 * <p>用于统一表达数据权限的分级语义，替代原有的整数常量</p>
 */
@Getter
public enum DataScopeLevel {

    /** 非数据范围权限（普通操作权限使用） */
    NONE(0, "非数据范围权限"),
    /** 仅查看自己的数据 */
    SELF(1, "仅查看自己的"),
    /** 查看标签范围内的数据 */
    TAGE(2, "查看标签的"),
    /** 查看全部数据 */
    ALL(3, "查看全部的");

    private final int code;
    private final String description;

    DataScopeLevel(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取对应的 DataScopeLevel
     *
     * @param code 级别编码
     * @return 对应的 DataScopeLevel，如果没有匹配则返回 NONE
     */
    public static DataScopeLevel fromCode(int code) {
        for (DataScopeLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return NONE;
    }
}
