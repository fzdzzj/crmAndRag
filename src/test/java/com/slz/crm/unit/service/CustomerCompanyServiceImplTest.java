package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
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

/** 客户公司服务单元测试：部门规范化与公司名+部门唯一约束。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户公司服务")
class CustomerCompanyServiceImplTest {

  @BeforeAll
  static void initializeCustomerCompanyTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerCompanyEntity.class);
  }

  @Mock private UserMapper userMapper;
  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private CustomerMergeLogMapper customerMergeLogMapper;
  @Mock private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
  @Mock private AOTOExcelUntil aotoExcelUntil;
  @Mock private CustomerContactMapper customerContactMapper;
  @Mock private CustomerContactService customerContactService;
  @Mock private SalesOpportunityService salesOpportunityService;
  @Mock private ContractOrderItemService contractOrderItemService;
  @Mock private DataConvertService dataConvertService;

  @Mock private CustomerIngestionService customerIngestionService;

  @Spy @InjectMocks private CustomerCompanyServiceImpl customerCompanyService;

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
    doAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerCompanyEntity.class).setId(101L);
              return true;
            })
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));

    CustomerCompanyVO created = customerCompanyService.add(companyDto("公司A", "  研发部  "));
    assertEquals(101L, created.getId());
    assertEquals("公司A", created.getCompanyName());

    ArgumentCaptor<CustomerCompanyEntity> captor =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
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

    ArgumentCaptor<CustomerCompanyEntity> captor =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyService).save(captor.capture());
    assertNull(captor.getValue().getDept());
  }

  @Test
  @DisplayName("同公司同部门新增被拒绝，不同部门允许")
  void addRejectsSameCompanySameDept() {
    authenticateAs(1L);
    when(customerCompanyMapper.selectCount(any())).thenReturn(1L);

    BaseException ex =
        assertThrows(
            BaseException.class, () -> customerCompanyService.add(companyDto("公司A", "研发部")));
    assertTrue(ex.getMessage().contains("已存在"));

    when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerCompanyEntity.class).setId(102L);
              return true;
            })
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));
    assertEquals(102L, customerCompanyService.add(companyDto("公司A", "市场部")).getId());
  }

  @Test
  @DisplayName("并发写入撞数据库唯一约束时转为业务提示")
  void addConvertsDuplicateKeyToBusinessError() {
    authenticateAs(1L);
    // 并发窗口：判重查询均通过，但 save 时数据库唯一约束兑底
    when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
    doThrow(new DuplicateKeyException("uk_company_name_dept"))
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));

    BaseException ex =
        assertThrows(
            BaseException.class, () -> customerCompanyService.add(companyDto("公司A", "研发部")));
    assertTrue(ex.getMessage().contains("已存在"));
  }

  @Test
  @DisplayName("判重查询按公司名+部门精确匹配，部门为空时保存 null")
  void duplicateCheckMatchesDeptExactly() {
    authenticateAs(1L);
    when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerCompanyEntity.class).setId(103L);
              return true;
            })
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));

    customerCompanyService.add(companyDto("公司C", null));

    verify(customerCompanyMapper)
        .selectCount(
            argThat(
                wrapper ->
                    wrapper.getSqlSegment().contains("company_name")
                        && wrapper.getSqlSegment().contains("dept")));
    verify(customerCompanyService)
        .save(argThat(entity -> ((CustomerCompanyEntity) entity).getDept() == null));
  }

  // ---------------- add-customer-semantic-retrieval 任务 2.3：语义向量 hook 触发路径 ----------------

  @Test
  @DisplayName("新增成功后回查完整实体并 upsert 语义向量")
  void addTriggersSemanticIngestAfterSave() {
    authenticateAs(1L);
    when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerCompanyEntity.class).setId(301L);
              return true;
            })
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));
    CustomerCompanyEntity full = new CustomerCompanyEntity();
    full.setId(301L);
    full.setCompanyName("回查公司");
    full.setIsDeleted(false);
    // spy 的 getById 依赖 ServiceImpl.baseMapper 注入，直接 stub spy 方法绕开
    doReturn(full).when(customerCompanyService).getById(301L);

    CustomerCompanyVO created = customerCompanyService.add(companyDto("回查公司", null));

    assertEquals(301L, created.getId());
    verify(customerIngestionService).ingest(full);
  }

  @Test
  @DisplayName("向量摄取异常不阻断新增主流程（WARN 降级，回埋端点兜底）")
  void addSurvivesIngestionFailure() {
    authenticateAs(1L);
    when(customerCompanyMapper.selectCount(any())).thenReturn(0L);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerCompanyEntity.class).setId(302L);
              return true;
            })
        .when(customerCompanyService)
        .save(any(CustomerCompanyEntity.class));
    CustomerCompanyEntity full = new CustomerCompanyEntity();
    full.setId(302L);
    full.setCompanyName("降级公司");
    full.setIsDeleted(false);
    doReturn(full).when(customerCompanyService).getById(302L);
    doThrow(new RuntimeException("向量库不可用"))
        .when(customerIngestionService)
        .ingest(any(CustomerCompanyEntity.class));

    CustomerCompanyVO created = customerCompanyService.add(companyDto("降级公司", null));

    assertEquals(302L, created.getId());
    verify(customerIngestionService).ingest(full);
  }

  @Test
  @DisplayName("级联逻辑删除后连带删除语义向量")
  void logicalDeleteCascadesVectorRemoval() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascade(CustomerCompanyEntity.class, 9L))
        .thenReturn(1);

    customerCompanyService.logicalDeleteById(9L);

    verify(customerIngestionService).deleteByCompanyId(9L);
  }
}
