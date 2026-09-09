package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商业活动联系人关联视图对象
 */
@Data
public class BusinessActivityContactVO {
    /**
     * 关联ID
     */
    private Long id;
    /**
     * 商业活动ID
     */
    private Long activityId;
    /**
     * 活动名称
     */
    private String activityName;
    /**
     * 联系人ID
     */
    private Long contactId;
    /**
     * 联系人角色
     */
    private String contactRole;
    /**
     * 创建人ID
     */
    private Long creatorId;
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    /**
     * 创建人姓名
     */
    private String creatorName;
    /**
     * 联系人姓名
     */
    private String contactName;

    /**
     * 从Entity创建VO，需要传入活动名称、联系人名称和创建人名称
     * @param entity 商业活动联系人关联实体
     * @param activityName 活动名称
     * @param contactName 联系人名称
     * @param creatorName 创建人名称
     * @return BusinessActivityContactVO
     */
    public static BusinessActivityContactVO fromEntity(BusinessActivityContactEntity entity, String activityName,
                                                       String contactName, String creatorName) {
        if (entity == null) {
            return null;
        }

        BusinessActivityContactVO vo = new BusinessActivityContactVO();
        vo.setId(entity.getId());
        vo.setActivityId(entity.getActivityId());
        vo.setActivityName(activityName);
        vo.setContactId(entity.getContactId());
        vo.setContactRole(entity.getContactRole());
        vo.setContactName(contactName);
        vo.setCreatorId(entity.getCreatorId());
        vo.setCreatorName(creatorName);
        vo.setCreateTime(entity.getCreateTime());

        return vo;
    }
}
