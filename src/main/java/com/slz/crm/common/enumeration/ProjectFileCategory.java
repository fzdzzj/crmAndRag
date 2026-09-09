package com.slz.crm.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目文件分类枚举
 */
@Getter
@AllArgsConstructor
public enum ProjectFileCategory {

    VISIT_RECORD("VISIT_RECORD", "拜访记录"),
    MEETING_MINUTES("MEETING_MINUTES", "交流纪要"),
    PROPOSAL("PROPOSAL", "方案"),
    BID_DOCUMENT("BID_DOCUMENT", "投标文件"),
    PROJECT_CONTRACT("PROJECT_CONTRACT", "项目合同");

    private final String code;
    private final String description;

    /**
     * 根据code查找枚举
     * @param code 分类编码
     * @return 枚举值，不存在返回null
     */
    public static ProjectFileCategory fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectFileCategory category : values()) {
            if (category.getCode().equals(code)) {
                return category;
            }
        }
        return null;
    }

    /**
     * 校验code是否有效
     * @param code 分类编码
     * @return true=有效
     */
    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }
}
