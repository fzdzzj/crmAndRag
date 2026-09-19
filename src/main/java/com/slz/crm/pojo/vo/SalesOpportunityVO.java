package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import lombok.Data;

/** 销售机会VO */
@Data
public class SalesOpportunityVO implements Privacy {
  private Long id;
  private String opportunityName;
  private Long companyId;
  private String companyName;
  private Long contactId;
  private String contactName;
  private int stage;
  private BigDecimal amount;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime expectedCloseDate;

  private String source;
  private String description;
  private Long ownerId;
  private String ownerName;
  private Long creatorId;
  private String creatorName;
  private Long approverId;
  private String approverName;

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

  /**
   * 从Entity创建VO，需要传入公司名称、联系人名称、负责人姓名、创建人姓名和审批人姓名
   *
   * @param entity 销售机会实体
   * @param companyName 公司名称
   * @param contactName 联系人姓名
   * @param ownerName 负责人姓名
   * @param creatorName 创建人姓名
   * @param approverName 审批人姓名
   * @return SalesOpportunityVO
   */
  public static SalesOpportunityVO fromEntity(
      SalesOpportunityEntity entity,
      String companyName,
      String contactName,
      String ownerName,
      String creatorName,
      String approverName) {
    if (entity == null) {
      return null;
    }

    SalesOpportunityVO vo = new SalesOpportunityVO();
    vo.setId(entity.getId());
    vo.setOpportunityName(entity.getOpportunityName());
    vo.setCompanyId(entity.getCompanyId());
    vo.setCompanyName(companyName);
    vo.setContactId(entity.getContactId());
    vo.setContactName(contactName);
    vo.setStage(entity.getStage());
    vo.setAmount(entity.getAmount());
    vo.setExpectedCloseDate(entity.getExpectedCloseDate());
    vo.setSource(entity.getSource());
    vo.setDescription(entity.getDescription());
    vo.setOwnerId(entity.getOwnerId());
    vo.setOwnerName(ownerName);
    vo.setCreatorId(entity.getCreatorId());
    vo.setCreatorName(creatorName);
    vo.setApproverId(entity.getApproverId());
    vo.setApproverName(approverName);
    vo.setCreateTime(entity.getCreateTime());
    vo.setUpdateTime(entity.getUpdateTime());

    return vo;
  }
}
