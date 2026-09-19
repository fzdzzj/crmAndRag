package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 三类协助来源的唯一关联对象解析实现。 */
@Service
public class AssistRelatedRecordResolverImpl implements AssistRelatedRecordResolver {

  @Autowired private SalesStageApprovalMapper salesStageApprovalMapper;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Override
  public AssistRelatedRecordVO resolve(AssistRequestEntity assist) {
    if (assist == null || assist.getModelName() == null || assist.getRecordId() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助关联记录不完整");
    }

    Long opportunityId = null;
    Long companyId = null;
    Long contactId = null;
    switch (assist.getModelName()) {
      case ModelName.SALES_STAGE_APPROVAL -> {
        SalesStageApprovalEntity approval =
            salesStageApprovalMapper.selectById(assist.getRecordId());
        if (approval != null) {
          opportunityId = approval.getOpportunityId();
        }
      }
      case ModelName.BUSINESS_ACTIVITY -> {
        BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
        if (activity != null) {
          opportunityId = activity.getOpportunityId();
          companyId = activity.getCompanyId();
        }
      }
      case ModelName.CONTACT_TASK -> {
        ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
        if (task != null) {
          opportunityId = task.getOpportunityId();
          companyId = task.getCompanyId();
          contactId = task.getContactId();
        }
      }
      default -> throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "暂不支持该协助来源的关联详情");
    }

    SalesOpportunityEntity opportunity =
        opportunityId == null ? null : salesOpportunityMapper.selectById(opportunityId);
    if (opportunity != null) {
      if (companyId == null) {
        companyId = opportunity.getCompanyId();
      }
      if (contactId == null) {
        contactId = opportunity.getContactId();
      }
    }

    AssistRelatedRecordVO result = new AssistRelatedRecordVO();
    result.setAssistId(assist.getId());
    result.setModelName(assist.getModelName());
    result.setRecordId(assist.getRecordId());
    result.setOpportunityId(opportunityId);
    result.setOpportunityName(opportunity == null ? null : opportunity.getOpportunityName());
    result.setCompanyId(companyId);
    result.setContactId(contactId);
    return result;
  }
}
