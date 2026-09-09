package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 添加用户到活动请求DTO
 */
@Data
public class AddActivityUserRequestDTO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户在活动中的角色（如：主持人/主讲人/记录人/陪同人），可选
     */
    private String userRole;
}
