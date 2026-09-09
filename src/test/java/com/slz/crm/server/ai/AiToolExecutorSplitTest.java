package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.PendingActionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiToolExecutorSplitTest {

    @Mock
    private CustomerCompanyService customerCompanyService;

    @Mock
    private PendingActionService pendingActionService;

    @InjectMocks
    private AiQueryToolExecutors queryExecutors;

    @InjectMocks
    private AiDraftToolExecutors draftExecutors;

    @Test
    void queryTool_usesQueryExecutor() {
        Page<CustomerCompanyVO> page = new Page<>(1, 2);
        page.setRecords(List.of());
        when(customerCompanyService.findCompany("公司", 1, 2)).thenReturn(page);

        Object result = queryExecutors.executeQueryCustomerCompany(
                Map.of("companyName", "公司", "pageNum", 1, "pageSize", 2), null);

        assertThat(result).isEqualTo(List.of());
        verify(customerCompanyService).findCompany("公司", 1, 2);
    }

    @Test
    void draftTool_usesDraftExecutor() throws Exception {
        RoleAO currentUser = new RoleAO();
        currentUser.setId(42L);
        BaseUnit.setCurrentRole(currentUser);
        AiDraftResult draftResult = AiDraftResult.builder().status("PENDING").build();
        when(pendingActionService.submitDraft(null, 42L, "CREATE_CUSTOMER", "{\"companyName\":\"公司\"}"))
                .thenReturn(draftResult);

        try {
            Object result = draftExecutors.executeCreateCustomerDraft(
                    Map.of("companyName", "公司"), null);

            assertThat(result).isSameAs(draftResult);
            verify(pendingActionService).submitDraft(null, 42L, "CREATE_CUSTOMER", "{\"companyName\":\"公司\"}");
        } finally {
            BaseUnit.removeCurrentId();
        }
    }
}
