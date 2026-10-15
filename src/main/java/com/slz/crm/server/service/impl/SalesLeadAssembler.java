package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.dto.LeadConvertDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesLeadEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.SalesLeadVO;
import com.slz.crm.server.mapper.UserMapper;
import java.math.BigDecimal;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;

/**
 * 销售线索装配支持类（add-lead-management-and-conversion-funnel；对齐 {@link CustomerCompanySupport} 先例，按 PMD
 * NCSS/复杂度约束从 SalesLeadServiceImpl 拆出）。纯静态、无状态，实体构建与 VO 装配所需 mapper 经参数传入。
 */
final class SalesLeadAssembler {

  private SalesLeadAssembler() {}

  /** 依据线索构建待入库的客户公司实体（未 insert，含默认公海私海/等级）。 */
  static CustomerCompanyEntity buildCompany(SalesLeadEntity lead, Long operatorId) {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setCompanyName(lead.getCompanyName());
    company.setSource(lead.getSource());
    company.setCreatorId(operatorId);
    company.setOwnerId(lead.getOwnerId() == null ? operatorId : lead.getOwnerId());
    company.setIsDeleted(false);
    company.setPoolStatus(0);
    company.setGrade(0);
    return company;
  }

  /** 依据线索构建待入库的首要联系人实体（未 insert）。 */
  static CustomerContactEntity buildContact(SalesLeadEntity lead, Long operatorId, Long companyId) {
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setCompanyId(companyId);
    contact.setName(lead.getName());
    contact.setPhone(lead.getPhone());
    contact.setMobile(lead.getPhone());
    contact.setEmail(lead.getEmail());
    contact.setCreatorId(operatorId);
    contact.setIsDeleted(false);
    return contact;
  }

  /** 依据线索与转化入参构建待入库的销售机会实体（未 insert；createOpportunity=false 时调用方传 null 跳过）。 */
  static SalesOpportunityEntity buildOpportunity(
      LeadConvertDTO dto, SalesLeadEntity lead, Long operatorId, Long companyId, Long contactId) {
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setOpportunityName(
        StringUtils.hasText(dto.getOpportunityName())
            ? dto.getOpportunityName()
            : lead.getCompanyName() + "-线索转化商机");
    opportunity.setCompanyId(companyId);
    opportunity.setContactId(contactId);
    opportunity.setStage(0);
    opportunity.setAmount(
        dto.getEstimatedAmount() == null ? BigDecimal.ZERO : dto.getEstimatedAmount());
    opportunity.setOwnerId(lead.getOwnerId() == null ? operatorId : lead.getOwnerId());
    opportunity.setCreatorId(operatorId);
    opportunity.setApproverId(operatorId);
    opportunity.setIsDeleted(false);
    return opportunity;
  }

  /**
   * 实体 → VO；负责人姓名反查填充（用户不存在或查无此人置空，不阻断主流程）。
   *
   * @param entity 线索实体
   * @param userMapper 用户查询 mapper（可为空，此时负责人姓名置空）
   */
  static SalesLeadVO toView(SalesLeadEntity entity, UserMapper userMapper) {
    SalesLeadVO vo = new SalesLeadVO();
    BeanUtils.copyProperties(entity, vo);
    if (entity.getOwnerId() != null && userMapper != null) {
      UserEntity owner = userMapper.selectById(entity.getOwnerId());
      if (owner != null) {
        vo.setOwnerName(owner.getRealName());
      }
    }
    return vo;
  }
}
