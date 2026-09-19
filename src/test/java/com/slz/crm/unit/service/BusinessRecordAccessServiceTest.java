package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.DataScopeService;
import com.slz.crm.server.service.impl.BusinessRecordAccessServiceImpl;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("业务详情对象级授权")
class BusinessRecordAccessServiceTest {

  @Mock private DataScopeService dataScopeService;
  @Mock private AssistScopeService assistScopeService;

  @InjectMocks private BusinessRecordAccessServiceImpl service;

  @BeforeEach
  void authenticate() {
    RoleAO current = new RoleAO();
    current.setId(7L);
    current.setRoleId(4L);
    BaseUnit.setCurrentRole(current);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("正常数据范围可读取，但不会误标为协助关系")
  void normalDataScopeDoesNotUnmaskAsAssist() {
    SalesOpportunityEntity opportunity = opportunity(99L);
    when(dataScopeService.canReadResource(
            any(), eq("sales_opportunity"), eq(99L), any(Long[].class)))
        .thenReturn(true);
    when(assistScopeService.visibleOpportunityIds(7L)).thenReturn(Collections.emptySet());

    assertFalse(service.assertCanReadOpportunity(opportunity));
  }

  @Test
  @DisplayName("待协助临时范围允许读取并标记为协助关系")
  void pendingAssistScopeAllowsRead() {
    SalesOpportunityEntity opportunity = opportunity(99L);
    when(dataScopeService.canReadResource(
            any(), eq("sales_opportunity"), eq(99L), any(Long[].class)))
        .thenReturn(false);
    when(assistScopeService.visibleOpportunityIds(7L)).thenReturn(Set.of(99L));

    assertTrue(service.assertCanReadOpportunity(opportunity));
  }

  @Test
  @DisplayName("正常权限与待协助权限同时存在时，按正常权限返回完整详情")
  void normalPermissionTakesPrecedenceOverAssistRestriction() {
    SalesOpportunityEntity opportunity = opportunity(99L);
    when(dataScopeService.canReadResource(
            any(), eq("sales_opportunity"), eq(99L), any(Long[].class)))
        .thenReturn(true);
    when(assistScopeService.visibleOpportunityIds(7L)).thenReturn(Set.of(99L));

    assertFalse(service.assertCanReadOpportunity(opportunity));
  }

  @Test
  @DisplayName("公司和联系人均不在正常或协助范围时拒绝")
  void unrelatedCompanyAndContactAreDenied() {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setId(60L);
    company.setCreatorId(8L);
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setId(70L);
    contact.setCreatorId(8L);

    when(dataScopeService.canReadResource(
            any(), eq("customer_company"), eq(60L), any(Long[].class)))
        .thenReturn(false);
    when(dataScopeService.canReadResource(
            any(), eq("customer_contact"), eq(70L), any(Long[].class)))
        .thenReturn(false);
    when(assistScopeService.visibleCompanyIds(anyLong())).thenReturn(Collections.emptySet());
    when(assistScopeService.visibleContactIds(anyLong())).thenReturn(Collections.emptySet());

    assertThrows(BaseException.class, () -> service.assertCanReadCompany(company));
    assertThrows(BaseException.class, () -> service.assertCanReadContact(contact));
  }

  private SalesOpportunityEntity opportunity(Long id) {
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(id);
    opportunity.setCreatorId(8L);
    opportunity.setOwnerId(9L);
    return opportunity;
  }
}
