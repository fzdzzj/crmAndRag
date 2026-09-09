package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商业活动用户关联数据传输对象
 */
@Data
public class BusinessActivityUserDTO {
    /**
     * 关联ID
     */
    private Long id;
    /**
     * 联系人角色
     */
    private String contactRole;
    /**
     * 商业活动ID
     */
    private Long activityId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户角色
     */
    private String userRole;
    /**
     * 创建人ID
     */
    private Long creatorId;
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
