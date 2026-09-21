package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.Data;

/** 客户联系人 VO */
@Data
public class CustomerContactVO implements Privacy {
  private Long id;
  private Long companyId;
  private String companyName;
  private String name;
  private String position;
  private String dept;
  private String phone;
  private String mobile;
  private String email;
  private Integer gender;

  /** 客户关系等级 */
  private Integer relationLevel;

  /** 备注列表 */
  private List<CustomerContactRemarkVO> remarks;

  private Long createId;

  /** 创建人姓名 */
  private String creatorName;

  private int isDeleted;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime updateTime;

  /** 视同本人的关联用户ID（协助人/参与人），用于隐私脱敏放行 */
  private Set<Long> relatedUserIds = Collections.emptySet();

  @Override
  public Set<Long> relatedUserIds() {
    return relatedUserIds;
  }

  @Override
  public Boolean email() {
    this.email = "***";
    return true;
  }

  @Override
  public Boolean mobile() {
    this.mobile = "***";
    return true;
  }

  @Override
  public Boolean phone() {
    this.phone = "***";
    return true;
  }

  @Override
  public Boolean position() {
    this.position = "***";
    return true;
  }

  /**
   * 从 Entity 创建 VO，需要传入公司名称
   *
   * @param entity 客户联系人实体
   * @param companyName 公司名称
   * @param creatorName 创建人姓名
   * @param remarkVOList 备注列表
   * @return CustomerContactVO
   */
  public static CustomerContactVO fromEntity(
      CustomerContactEntity entity,
      String companyName,
      String creatorName,
      List<CustomerContactRemarkVO> remarkVOList) {
    CustomerContactVO vo = null;
    if (entity != null) {
      vo = new CustomerContactVO();
      vo.setId(entity.getId());
      vo.setCompanyId(entity.getCompanyId());
      vo.setCompanyName(companyName);
      vo.setName(entity.getName());
      vo.setPosition(entity.getPosition());
      vo.setDept(entity.getDept());
      vo.setPhone(entity.getPhone());
      vo.setMobile(entity.getMobile());
      vo.setEmail(entity.getEmail());
      vo.setGender(entity.getGender());
      vo.setRelationLevel(entity.getRelationLevel());
      vo.setCreateId(entity.getCreatorId());
      vo.setCreatorName(creatorName);
      vo.setIsDeleted(entity.getIsDeleted() != null && entity.getIsDeleted() ? 1 : 0);
      vo.setCreateTime(entity.getCreateTime());
      vo.setUpdateTime(entity.getUpdateTime());
      vo.setRemarks(remarkVOList);
    }
    return vo;
  }
}
