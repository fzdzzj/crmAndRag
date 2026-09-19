package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.impl.AssistRelatedRecordResolverImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("协助关联对象统一解析")
class AssistRelatedRecordResolverTest {

  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @InjectMocks private AssistRelatedRecordResolverImpl resolver;

  @Test
  @DisplayName("联络任务直接公司和联系人优先于关联商机")
  void taskDirectCompanyAndContactTakePriority() {
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setId(1L);
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(10L);

    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(10L);
    task.setOpportunityId(20L);
    task.setCompanyId(30L);
    task.setContactId(40L);
    when(contactTaskMapper.selectById(10L)).thenReturn(task);

    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(20L);
    opportunity.setCompanyId(31L);
    opportunity.setContactId(41L);
    when(salesOpportunityMapper.selectById(20L)).thenReturn(opportunity);

    AssistRelatedRecordVO result = resolver.resolve(assist);

    assertEquals(20L, result.getOpportunityId());
    assertEquals(30L, result.getCompanyId());
    assertEquals(40L, result.getContactId());
  }

  @Test
  @DisplayName("联络任务缺少直接关联时才由商机补齐")
  void taskUsesOpportunityOnlyForMissingDirectReferences() {
    AssistRequestEntity assist = new AssistRequestEntity();
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(10L);

    ContactTaskEntity task = new ContactTaskEntity();
    task.setOpportunityId(20L);
    task.setCompanyId(30L);
    when(contactTaskMapper.selectById(10L)).thenReturn(task);

    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(20L);
    opportunity.setCompanyId(31L);
    opportunity.setContactId(41L);
    when(salesOpportunityMapper.selectById(20L)).thenReturn(opportunity);

    AssistRelatedRecordVO result = resolver.resolve(assist);

    assertEquals(30L, result.getCompanyId());
    assertEquals(41L, result.getContactId());
  }
}
