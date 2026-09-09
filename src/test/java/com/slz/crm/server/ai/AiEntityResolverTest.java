package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.SalesOpportunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiEntityResolver 单测：合同/商机/客户三类解析的 RESOLVED/MISSING/NOT_FOUND/AMBIGUOUS/FALLBACK 路径
 */
@ExtendWith(MockitoExtension.class)
class AiEntityResolverTest {

    @Mock
    private ContractService contractService;

    @Mock
    private SalesOpportunityService salesOpportunityService;

    @Mock
    private CustomerCompanyService customerCompanyService;

    @InjectMocks
    private AiEntityResolver resolver;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(resolver, "aiProperties", new AiProperties());
    }

    @Test
    void contractIdExists_resolved() {
        AiEntityResolver.Resolution result = resolver.resolveContract(5L, null);

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.RESOLVED);
        assertThat(result.getResolvedId()).isEqualTo(5L);
    }

    @Test
    void contractIdNotFound_notFound() {
        when(contractService.getContractById(999L)).thenThrow(new BaseException(ErrorCode.CONTRACT_NOT_EXISTS));

        AiEntityResolver.Resolution result = resolver.resolveContract(999L, null);

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.NOT_FOUND);
    }

    @Test
    void contractLookupUnexpectedError_propagates() {
        when(contractService.getContractById(999L)).thenThrow(new RuntimeException("数据库不可用"));

        assertThatThrownBy(() -> resolver.resolveContract(999L, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("数据库不可用");
    }

    @Test
    void contractBothMissing_missing() {
        AiEntityResolver.Resolution result = resolver.resolveContract(null, "  ");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.MISSING);
    }

    @Test
    void contractNameUniqueHit_resolved() {
        when(contractService.contractQuery(anyInt(), anyInt(), any(ContractDTO.class)))
                .thenReturn(contractPage(contract(7L, "C-001", "某合同")));

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "某合同");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.RESOLVED);
        assertThat(result.getResolvedId()).isEqualTo(7L);
    }

    @Test
    void contractNameMultiHit_ambiguous() {
        when(contractService.contractQuery(anyInt(), anyInt(), any(ContractDTO.class)))
                .thenReturn(contractPage(contract(1L, "C-001", "合同A"), contract(2L, "C-002", "合同B")));

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "合同");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.AMBIGUOUS);
        assertThat(result.getCandidates()).hasSize(2);
    }

    @Test
    void contractNameZeroHit_fallbackAll() {
        // 首次按名称查零命中，补救查全部命中两条
        when(contractService.contractQuery(anyInt(), anyInt(), any(ContractDTO.class)))
                .thenReturn(contractPage())
                .thenReturn(contractPage(contract(1L, "C-001", "合同A"), contract(2L, "C-002", "合同B")));

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "不存在");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.FALLBACK);
        assertThat(result.getCandidates()).hasSize(2);
    }

    @Test
    void contractNameZeroHitAndNoContract_notFound() {
        when(contractService.contractQuery(anyInt(), anyInt(), any(ContractDTO.class)))
                .thenReturn(contractPage());

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "不存在");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.NOT_FOUND);
    }

    @Test
    void nameQuery_usesConfiguredPageSize() {
        AiProperties properties = new AiProperties();
        properties.setEntityQueryPageSize(25);
        ReflectionTestUtils.setField(resolver, "aiProperties", properties);

        when(contractService.contractQuery(eq(1), eq(25), any(ContractDTO.class)))
                .thenReturn(contractPage(contract(7L, "C-001", "某合同")));

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "某合同");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.RESOLVED);
        verify(contractService).contractQuery(eq(1), eq(25), any(ContractDTO.class));
    }

    @Test
    void fallbackQuery_cappedAt50() {
        when(contractService.contractQuery(anyInt(), anyInt(), any(ContractDTO.class)))
                .thenReturn(contractPage());
        when(contractService.contractQuery(eq(1), eq(50), any(ContractDTO.class)))
                .thenReturn(contractPage(contract(1L, "C-001", "合同A"), contract(2L, "C-002", "合同B")));

        AiEntityResolver.Resolution result = resolver.resolveContract(null, "不存在");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.FALLBACK);
        assertThat(result.getCandidates()).hasSize(2);
        verify(contractService).contractQuery(eq(1), eq(50), any(ContractDTO.class));
    }

    @Test
    void opportunityNameUniqueHit_resolved() {
        when(salesOpportunityService.getSalesOpportunityByQuery(any(SalesOpportunityQueryDTO.class), anyInt(), anyInt()))
                .thenReturn(opportunityPage(opportunity(3L, "商机A", "公司X")));

        AiEntityResolver.Resolution result = resolver.resolveOpportunity(null, "商机A", null, null);

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.RESOLVED);
        assertThat(result.getResolvedId()).isEqualTo(3L);
    }

    @Test
    void opportunityAllMissing_missing() {
        AiEntityResolver.Resolution result = resolver.resolveOpportunity(null, null, "", null);

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.MISSING);
    }

    @Test
    void customerCompanyUniqueHit_resolved() {
        when(customerCompanyService.findCompany(eq("测试公司"), anyInt(), anyInt()))
                .thenReturn(companyPage(company(6L, "测试公司")));

        AiEntityResolver.Resolution result = resolver.resolveCustomerCompany(null, "测试公司");

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.RESOLVED);
        assertThat(result.getResolvedId()).isEqualTo(6L);
    }

    @Test
    void customerCompanyIdNotFound_notFound() {
        when(customerCompanyService.getCompanyByCondition(888L)).thenReturn(null);

        AiEntityResolver.Resolution result = resolver.resolveCustomerCompany(888L, null);

        assertThat(result.getStatus()).isEqualTo(AiEntityResolver.Status.NOT_FOUND);
    }

    // ==================== 构造辅助 ====================

    private ContractVO contract(Long id, String no, String name) {
        ContractVO vo = new ContractVO();
        vo.setId(id);
        vo.setContractNo(no);
        vo.setContractName(name);
        return vo;
    }

    private SalesOpportunityVO opportunity(Long id, String name, String company) {
        SalesOpportunityVO vo = new SalesOpportunityVO();
        vo.setId(id);
        vo.setOpportunityName(name);
        vo.setCompanyName(company);
        return vo;
    }

    private CustomerCompanyVO company(Long id, String name) {
        CustomerCompanyVO vo = new CustomerCompanyVO();
        vo.setId(id);
        vo.setCompanyName(name);
        return vo;
    }

    @SafeVarargs
    private Page<ContractVO> contractPage(ContractVO... records) {
        Page<ContractVO> page = new Page<>();
        page.setRecords(List.of(records));
        return page;
    }

    @SafeVarargs
    private Page<SalesOpportunityVO> opportunityPage(SalesOpportunityVO... records) {
        Page<SalesOpportunityVO> page = new Page<>();
        page.setRecords(List.of(records));
        return page;
    }

    @SafeVarargs
    private Page<CustomerCompanyVO> companyPage(CustomerCompanyVO... records) {
        Page<CustomerCompanyVO> page = new Page<>();
        page.setRecords(List.of(records));
        return page;
    }
}
