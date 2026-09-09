package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 客户联系人备注 VO
 */
@Data
public class CustomerContactRemarkVO {

    /**
     * 备注 ID
     */
    private Long id;

    /**
     * 联系人 ID
     */
    private Long contactId;

    /**
     * 备注类型
     */
    private Integer remarkType;

    /**
     * 备注类型描述
     */
    private String remarkTypeDesc;

    /**
     * 备注内容
     */
    private String remarkContent;

    /**
     * 备注姓名
     */
    private String remarkName;

    /**
     * 备注出生日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate remarkDate;

    /**
     * 创建人 ID
     */
    private Long creatorId;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    /**
     * 修改时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;


    /**
     * 将entity转换为VO
     * @param entityList
     * @return
     */
    public static List<CustomerContactRemarkVO> fromEntity(List<CustomerContactRemarkEntity> entityList) {
        if (entityList == null) {
            return null;
        }

        return entityList.stream().map(entity -> {
            CustomerContactRemarkVO vo = new CustomerContactRemarkVO();
            vo.setId(entity.getId());
            vo.setContactId(entity.getContactId());
            vo.setRemarkType(entity.getRemarkType());
            vo.setRemarkContent(entity.getRemarkContent());
            vo.setRemarkName(entity.getRemarkName());
            vo.setRemarkDate(entity.getRemarkDate());
            vo.setCreatorId(entity.getCreatorId());
            vo.setCreateTime(entity.getCreateTime());
            vo.setUpdateTime(entity.getUpdateTime());

            switch (entity.getRemarkType()) {
                case 1:
                    vo.setRemarkTypeDesc("喜好");
                    break;
                case 2:
                    vo.setRemarkTypeDesc("住址");
                    break;
                case 3:
                    vo.setRemarkTypeDesc("本人出生日期");
                    break;
                case 4:
                    vo.setRemarkTypeDesc("亲属出生日期");
                    break;
                case 5:
                    vo.setRemarkTypeDesc("自定义");
            }

            return vo;
        }).collect(Collectors.toList());
    }
}
