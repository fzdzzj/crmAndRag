package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.ContractEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 合同VO */
@Data
public class ContractVO implements Privacy {

  private Long id;

  /** 合同编号 */
  private String contractNo;

  /** 商机id */
  private Long opportunityId;

  /** 商机名称 */
  private String opportunityName;

  /** 公司客户id */
  private Long companyId;

  /** 公司名称 */
  private String companyName;

  /** 合同名称 */
  private String contractName;

  /** 合同金额 */
  private BigDecimal totalAmount;

  /** 签约日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime signDate;

  /** 合同生效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startDate;

  /** 合同失效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endDate;

  /** 合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用） */
  private Integer contractStatus;

  /** 合同状态描述 */
  private String contractStatusDesc;

  /** 负责人id */
  private Long ownerId;

  /** 负责人姓名 */
  private String ownerName;

  /** 创建人id */
  private Long creatorId;

  /** 创建人姓名 */
  private String creatorName;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime updateTime;

  /**
   * 从Entity创建VO，需要传入公司名称和商机名称
   *
   * @param entity 合同实体
   * @param companyName 公司名称
   * @param opportunityName 商机名称
   * @param ownerName 负责人姓名
   * @param creatorName 创建人姓名
   * @return ContractVO
   */
  public static ContractVO fromEntity(
      ContractEntity entity,
      String companyName,
      String opportunityName,
      String ownerName,
      String creatorName) {
    ContractVO vo = null;
    if (entity != null) {
      vo = new ContractVO();
      vo.setId(entity.getId());
      vo.setContractNo(entity.getContractNo());
      vo.setOpportunityId(entity.getOpportunityId());
      vo.setOpportunityName(opportunityName);
      vo.setCompanyId(entity.getCompanyId());
      vo.setCompanyName(companyName);
      vo.setContractName(entity.getContractName());
      vo.setTotalAmount(entity.getTotalAmount());
      vo.setSignDate(entity.getSignDate() != null ? entity.getSignDate() : null);
      vo.setStartDate(entity.getStartDate() != null ? entity.getStartDate() : null);
      vo.setEndDate(entity.getEndDate() != null ? entity.getEndDate() : null);
      vo.setContractStatus(entity.getContractStatus());
      // 设置合同状态描述
      String statusDesc =
          switch (entity.getContractStatus()) {
            case 0 -> "预签约";
            case 1 -> "已生效";
            case 2 -> "已终止";
            case 3 -> "已完成";
            case 4 -> "已弃用";
            default -> "未知状态";
          };
      vo.setContractStatusDesc(statusDesc);
      vo.setOwnerId(entity.getOwnerId());
      vo.setOwnerName(ownerName);
      vo.setCreatorId(entity.getCreatorId());
      vo.setCreatorName(creatorName);
      vo.setCreateTime(entity.getCreateTime());
      vo.setUpdateTime(entity.getUpdateTime());
    }
    return vo;
  }
}
