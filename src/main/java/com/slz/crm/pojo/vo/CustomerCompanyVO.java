package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;

/**
 * 客户公司VO类
 */
@Data
public class CustomerCompanyVO implements Privacy {
    private Long id;
    private String companyName;
    private String belongGroup;
    private String dept;
    private String industry;
    private String customerType;
    private String address;
    private String phone;
    private String website;
    private String description;
    private Long creatorId;
    private Integer grade;
    /**
     * 公司创建人姓名
     */
    private String creatorName;
    /**
     * 公司负责人姓名
     */
    private String ownerName;
    private Long ownerId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
    private Boolean isDeleted;

    /**
     * 视同本人的关联用户ID（协助人/参与人），用于隐私脱敏放行
     */
    private Set<Long> relatedUserIds = Collections.emptySet();

    @Override
    public Set<Long> relatedUserIds() {
        return relatedUserIds;
    }

    @Override
    public Boolean phone(){
        this.phone = "***";
        return true;
    }

    @Override
    public Boolean website(){
        this.website = "***";
        return true;
    }

    /**
     * 从Entity创建VO，需要传入创建人名称和负责人名称
     * @param entity 客户公司实体
     * @param creatorName 创建人姓名
     * @param ownerName 负责人姓名
     * @return CustomerCompanyVO
     */
    public static CustomerCompanyVO fromEntity(CustomerCompanyEntity entity, String creatorName, String ownerName) {
        if (entity == null) {
            return null;
        }

        CustomerCompanyVO vo = new CustomerCompanyVO();
        vo.setId(entity.getId());
        vo.setCompanyName(entity.getCompanyName());
        vo.setIndustry(entity.getIndustry());
        vo.setCustomerType(entity.getCustomerType());
        vo.setBelongGroup(entity.getBelongGroup());
        vo.setDept(entity.getDept());
        vo.setAddress(entity.getAddress());
        vo.setPhone(entity.getPhone());
        vo.setWebsite(entity.getWebsite());
        vo.setDescription(entity.getDescription());
        vo.setCreatorId(entity.getCreatorId());
        vo.setOwnerId(entity.getOwnerId());
        vo.setGrade(entity.getGrade());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setIsDeleted(entity.getIsDeleted());
        vo.setCreatorName(creatorName);
        vo.setOwnerName(ownerName);

        return vo;
    }


}
