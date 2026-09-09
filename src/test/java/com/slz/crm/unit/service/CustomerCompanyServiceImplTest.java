package com.slz.crm.unit.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.CustomerMergeLogMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.SalesOpportunityService;
import com.slz.crm.server.service.impl.CustomerCompanyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户公司服务单元测试：部门规范化与公司名+部门唯一约束。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户公司服务")
class CustomerCompanyServiceImplTest {

    @BeforeAll
    static void initializeCustomerCompanyTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CustomerCompanyEntity.class);
    }

    @Mock
    private UserMapper userMapper;
    @Mock
    private CustomerCompanyMapper customerCompanyMapper;
    @Mock
    private CustomerMergeLogMapper customerMergeLogMapper;
    @Mock
    private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
    @Mock
    private AOTOExcelUntil aotoExcelUntil;
    @Mock
    private CustomerContactMapper customerContactMapper;
    @Mock
    private CustomerContactService customerContactService;
    @Mock
    private SalesOpportunityService salesOpportunityService;
    @Mock
    private ContractOrderItemService contractOrderItemService;
    @Mock
    private DataConvertService dataConvertService;

    @Spy
    @InjectMocks
    private CustomerCompanyServiceImpl customerCompanyService;

    @AfterEach
    void clearCurrentUser() {
        BaseUnit.removeCurrentId();
    }

    private void authenticateAs(Long userId) {
        RoleAO role = new RoleAO();
        role.setId(userId);
        BaseUnit.setCurrentRole(role);
    }

    private CustomerCompanyDTO companyDto(String companyName, String dept) {
        CustomerCompanyDTO dto = new CustomerCompanyDTO();
        dto.setCompanyName(companyName);
        dto.setDept(dept);
        return dto;
    }

    @Test
    @DisplayName("新增公司时部门去除首尾空格后保存")
    void addNormalizesDeptWithTrim() {
        authenticateAs(1L);
        when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, CustomerCompanyEntity.class).setId(101L);
            return true;
        }).when(customerCompanyService).save(any(CustomerCompanyEntity.class));

        CustomerCompanyVO created = customerCompanyService.add(companyDto("公司A", "  研发部  "));
        assertEquals(101L, created.getId());
        assertEquals("公司A", created.getCompanyName());

        ArgumentCaptor<CustomerCompanyEntity> captor = ArgumentCaptor.forClass(CustomerCompanyEntity.class);
        verify(customerCompanyService).save(captor.capture());
        assertEquals("研发部", captor.getValue().getDept());
    }

    @Test
    @DisplayName("新增公司时空白部门规范化为 null")
    void addNormalizesBlankDeptToNull() {
        authenticateAs(1L);
        when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
        doReturn(true).when(customerCompanyService).save(any(CustomerCompanyEntity.class));

        customerCompanyService.add(companyDto("公司B", "   "));

        ArgumentCaptor<CustomerCompanyEntity> captor = ArgumentCaptor.forClass(CustomerCompanyEntity.class);
        verify(customerCompanyService).save(captor.capture());
        assertNull(captor.getValue().getDept());
    }

    @Test
    @DisplayName("同公司同部门新增被拒绝，不同部门允许")
    void addRejectsSameCompanySameDept() {
        authenticateAs(1L);
        when(customerCompanyMapper.selectCount(any())).thenReturn(1L);

        BaseException ex = assertThrows(BaseException.class,
                () -> customerCompanyService.add(companyDto("公司A", "研发部")));
        assertTrue(ex.getMessage().contains("已存在"));

        when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, CustomerCompanyEntity.class).setId(102L);
            return true;
        }).when(customerCompanyService).save(any(CustomerCompanyEntity.class));
        assertEquals(102L, customerCompanyService.add(companyDto("公司A", "市场部")).getId());
    }

    @Test
    @DisplayName("并发写入撞数据库唯一约束时转为业务提示")
    void addConvertsDuplicateKeyToBusinessError() {
        authenticateAs(1L);
        // 并发窗口：判重查询均通过，但 save 时数据库唯一约束兑底
        when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_company_name_dept"))
                .when(customerCompanyService).save(any(CustomerCompanyEntity.class));
    
        BaseException ex = assertThrows(BaseException.class,
                () -> customerCompanyService.add(companyDto("公司A", "研发部")));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("判重查询按公司名+部门精确匹配，部门为空时保存 null")
    void duplicateCheckMatchesDeptExactly() {
        authenticateAs(1L);
        when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, CustomerCompanyEntity.class).setId(103L);
            return true;
        }).when(customerCompanyService).save(any(CustomerCompanyEntity.class));

        customerCompanyService.add(companyDto("公司C", null));

        verify(customerCompanyMapper).selectCount(argThat(wrapper ->
                wrapper.getSqlSegment().contains("company_name")
                        && wrapper.getSqlSegment().contains("dept")));
        verify(customerCompanyService).save(argThat(entity ->
                ((CustomerCompanyEntity) entity).getDept() == null));
    }
}
