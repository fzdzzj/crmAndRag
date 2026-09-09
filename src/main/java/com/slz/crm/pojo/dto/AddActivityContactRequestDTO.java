package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 添加联系人到活动请求DTO
 */
@Data
public class AddActivityContactRequestDTO {

    /**
     * 联系人ID
     */
    private Long contactId;

    /**
     * 联系人在活动中的角色（如：决策者/技术对接人/参会人），可选
     */
    private String contactRole;
}
