package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataScopeService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 统一组合正常数据范围与待协助临时范围。
 *
 * <p>普通列表仍只使用正常数据范围；待协助关系仅在单条详情授权时作为补充。
 */
@Service
@RequiredArgsConstructor
public class BusinessRecordAccessServiceImpl implements BusinessRecordAccessService {

  private static final String OPPORTUNITY_RESOURCE = "sales_opportunity";
  private static final String COMPANY_RESOURCE = "customer_company";
  private static final String CONTACT_RESOURCE = "customer_contact";

  private final DataScopeService dataScopeService;
  private final AssistScopeService assistScopeService;

  @Override
  public boolean assertCanReadOpportunity(SalesOpportunityEntity opportunity) {
    RoleAO current = requireCurrentUser();
    boolean inDataScope =
        dataScopeService.canReadResource(
            current,
            OPPORTUNITY_RESOURCE,
            opportunity.getId(),
            opportunity.getCreatorId(),
            opportunity.getOwnerId());
    boolean inAssistScope =
        visibleIds(assistScopeService.visibleOpportunityIds(current.getId()))
            .contains(opportunity.getId());
    assertAllowed(inDataScope || inAssistScope);
    // 仅当正常数据范围没有放行、而协助关系放行时，才属于临时协助授权。
    // 调用方据此收紧详情中的活动/审批记录，避免将正常业务用户误判为协助人。
    return !inDataScope && inAssistScope;
  }

  @Override
  public boolean assertCanReadCompany(CustomerCompanyEntity company) {
    RoleAO current = requireCurrentUser();
    boolean inDataScope =
        dataScopeService.canReadResource(
            current, COMPANY_RESOURCE, company.getId(), company.getCreatorId());
    boolean inAssistScope =
        visibleIds(assistScopeService.visibleCompanyIds(current.getId())).contains(company.getId());
    assertAllowed(inDataScope || inAssistScope);
    return inAssistScope;
  }

  @Override
  public boolean assertCanReadContact(CustomerContactEntity contact) {
    RoleAO current = requireCurrentUser();
    boolean inDataScope =
        dataScopeService.canReadResource(
            current, CONTACT_RESOURCE, contact.getId(), contact.getCreatorId());
    boolean inAssistScope =
        visibleIds(assistScopeService.visibleContactIds(current.getId())).contains(contact.getId());
    assertAllowed(inDataScope || inAssistScope);
    return inAssistScope;
  }

  private RoleAO requireCurrentUser() {
    RoleAO current = BaseUnit.getCurrentRole();
    if (current == null || current.getId() == null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    return current;
  }

  private Set<Long> visibleIds(Set<Long> ids) {
    return ids == null ? Set.of() : ids;
  }

  private void assertAllowed(boolean allowed) {
    if (!allowed) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权查看该业务记录");
    }
  }
}
