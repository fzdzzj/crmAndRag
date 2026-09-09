package com.slz.crm.unit.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import com.slz.crm.server.service.impl.AssistScopeServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("协助实时可见范围")
class AssistScopeServiceTest {

    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AssistRequestEntity.class);
    }

    @Mock
    private AssistRequestMapper assistRequestMapper;
    @Mock
    private SalesStageApprovalMapper salesStageApprovalMapper;
    @Mock
    private BusinessActivityMapper businessActivityMapper;
    @Mock
    private ContactTaskMapper contactTaskMapper;
    @Mock
    private SalesOpportunityMapper salesOpportunityMapper;
    @Mock
    private AssistRelatedRecordResolver assistRelatedRecordResolver;

    @InjectMocks
    private AssistScopeServiceImpl service;

    @Test
    @DisplayName("终态协助不再授予公司联系人和商机的实时可见性")
    void relatedRecordsAreRestrictedToPendingStatus() {
        when(assistRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        service.visibleOpportunityIds(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AssistRequestEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(assistRequestMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("assist_status"));
    }

    @Test
    @DisplayName("可见范围复用统一解析结果，不额外回查商机或任务")
    void visibleScopeUsesSharedResolverResult() {
        AssistRequestEntity assist = new AssistRequestEntity();
        assist.setId(1L);
        when(assistRequestMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(assist));

        AssistRelatedRecordVO related = new AssistRelatedRecordVO();
        related.setCompanyId(30L);
        when(assistRelatedRecordResolver.resolve(assist)).thenReturn(related);

        assertTrue(service.visibleCompanyIds(9L).contains(30L));
    }
}
