package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.CustomerContactServiceImpl;
import java.util.List;
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

/**
 * 客户联系人服务语义 hook 单元测试（add-customer-contact-retrieval 任务 1.4）。
 *
 * <p>覆盖联系人 CRUD 增删改（含软删）事务提交后触发所属 company 重摄取：复用 {@link
 * CustomerSemanticHookSupport#runAfterCommit}（无事务测试上下文「无同步→立即同步」分支）→ 回查所属公司完整实体 →
 * {@link CustomerIngestionService#ingest}；以及无 embedding 配置（customerIngestionService 未装配）时静默降级不致
 * 既有 IT 失败。全部零外呼（mock 桩）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户联系人服务语义 hook")
class CustomerContactServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerContactEntity.class);
  }

  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private CustomerContactMapper customerContactMapper;
  @Mock private DataConvertService dataConvertService;
  @Mock private BusinessRecordAccessService businessRecordAccessService;
  @Mock private CustomerIngestionService customerIngestionService;

  @Spy @InjectMocks private CustomerContactServiceImpl contactService;

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }

  private CustomerCompanyEntity fullCompany(long id) {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setId(id);
    company.setCompanyName("回查公司");
    company.setIsDeleted(false);
    return company;
  }

  @Test
  @DisplayName("新增联系人后事务提交触发所属公司重摄取")
  void insertTriggersParentCompanyReingest() {
    authenticateAs(1L);
    when(customerCompanyMapper.selectById(501L)).thenReturn(fullCompany(501L));
    when(customerContactMapper.insert(any(CustomerContactEntity.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerContactEntity.class).setId(77L);
              return 1;
            });

    CustomerContactDTO dto = new CustomerContactDTO();
    dto.setCompanyId(501L);
    dto.setName("张伟");
    CustomerContactVO created = contactService.insert(dto);

    assertEquals(77L, created.getId());
    verify(customerCompanyMapper).selectById(501L);
    verify(customerIngestionService).ingest(fullCompany(501L));
  }

  @Test
  @DisplayName("软删联系人后事务提交触发所属公司重摄取（联系人出语义段）")
  void softDeleteTriggersParentCompanyReingest() {
    authenticateAs(1L);
    when(contactService.getById(9L)).thenReturn(contactEntity(9L, 502L));
    when(customerCompanyMapper.selectById(502L)).thenReturn(fullCompany(502L));

    int count = contactService.deleteOrRecoverByIds(List.of(9L), true);

    assertEquals(1, count);
    verify(customerCompanyMapper).selectById(502L);
    verify(customerIngestionService).ingest(fullCompany(502L));
  }

  @Test
  @DisplayName("customerIngestionService 未装配（无 embedding 测试环境）hook 静默跳过不致失败")
  void missingIngestionServiceSkipsHookQuietly() {
    authenticateAs(1L);
    // 将 spy 的 customerIngestionService 置空，模拟无 embedding 依赖装配的测试环境
    setField(contactService, "customerIngestionService", null);
    when(customerContactMapper.insert(any(CustomerContactEntity.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, CustomerContactEntity.class).setId(78L);
              return 1;
            });

    CustomerContactDTO dto = new CustomerContactDTO();
    dto.setCompanyId(503L);
    dto.setName("李娜");
    CustomerContactVO created = contactService.insert(dto);

    assertEquals(78L, created.getId());
    verify(customerIngestionService, never()).ingest(any());
  }

  private CustomerContactEntity contactEntity(long id, long companyId) {
    CustomerContactEntity c = new CustomerContactEntity();
    c.setId(id);
    c.setCompanyId(companyId);
    c.setName("张伟");
    return c;
  }

  /** 反射写字段（@Spy/@InjectMocks 装配后可覆写受注入字段）。 */
  private void setField(Object target, String name, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getSuperclass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}