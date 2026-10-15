package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.LeadConvertDTO;
import com.slz.crm.pojo.dto.SalesLeadDTO;
import com.slz.crm.pojo.dto.SalesLeadQueryDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesLeadEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.LeadConvertVO;
import com.slz.crm.pojo.vo.SalesLeadVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesLeadMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 销售线索业务服务单测（add-lead-management-and-conversion-funnel 任务 5.1）。
 *
 * <p>覆盖线索录入、状态流转（跟进修改/无效关闭）、分页查询，以及核心「一键转化」——单事务内创建客户公司、 首要联系人及可选销售机会并回填已转化状态。通过 {@code
 * BaseUnit.setCurrentRole} 驱动当前用户，mock mapper 隔离数据库；语义向量 hook 依赖未装配时整体跳过（不阻断业务流）。
 */
@DisplayName("销售线索业务服务")
@ExtendWith(MockitoExtension.class)
class SalesLeadServiceTest {

  @Mock private SalesLeadMapper salesLeadMapper;
  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private CustomerContactMapper customerContactMapper;
  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private UserMapper userMapper;

  @InjectMocks private SalesLeadServiceImpl service;

  private static final long OPERATOR_ID = 9001L;

  @BeforeEach
  void setUp() {
    RoleAO current = new RoleAO();
    current.setId(OPERATOR_ID);
    current.setRoleId(5L); // 普通业务员
    BaseUnit.setCurrentRole(current);
    // MyBatis-Plus ServiceImpl 的继承 baseMapper 不会被 @InjectMocks 注入，需显式回填，
    // 否则 getById/save/updateById/page 等继承方法经 getBaseMapper() 取到 null（同 PendingActionServiceImplTest
    // 做法）。
    ReflectionTestUtils.setField(service, "baseMapper", salesLeadMapper);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  private SalesLeadEntity lead(Long id, Integer status, Long ownerId) {
    SalesLeadEntity e = new SalesLeadEntity();
    e.setId(id);
    e.setName("张三");
    e.setCompanyName("示例科技有限公司");
    e.setPhone("13800000000");
    e.setEmail("zhangsan@example.com");
    e.setSource("展会");
    e.setStatus(status == null ? 0 : status);
    e.setOwnerId(ownerId);
    e.setCreatorId(OPERATOR_ID);
    e.setIsDeleted(0);
    return e;
  }

  @Test
  @DisplayName("录入：合法线索落库，默认状态0待跟进，负责人缺省为当前用户")
  void add_happyPath_defaultsStatusAndOwner() {
    SalesLeadDTO dto = new SalesLeadDTO();
    dto.setName("李四");
    dto.setCompanyName("示例贸易公司");
    dto.setPhone("13911111111");

    when(salesLeadMapper.insert(any(SalesLeadEntity.class)))
        .thenAnswer(
            inv -> {
              SalesLeadEntity e = inv.getArgument(0);
              e.setId(100L);
              return 1;
            });

    SalesLeadVO vo = service.add(dto);

    assertNotNull(vo);
    assertEquals(100L, vo.getId());
    assertEquals("李四", vo.getName());
    assertEquals(0, vo.getStatus());
    assertEquals(OPERATOR_ID, vo.getOwnerId());
    assertEquals(OPERATOR_ID, vo.getCreatorId());

    ArgumentCaptor<SalesLeadEntity> captor = ArgumentCaptor.forClass(SalesLeadEntity.class);
    verify(salesLeadMapper).insert(captor.capture());
    assertEquals(0, captor.getValue().getIsDeleted());
  }

  @Test
  @DisplayName("录入：姓名或公司名缺失抛参数为空")
  void add_missingKeyField_throws() {
    SalesLeadDTO dto = new SalesLeadDTO();
    dto.setName("只填姓名");
    BaseException ex = assertThrows(BaseException.class, () -> service.add(dto));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
    verify(salesLeadMapper, never()).insert(any());
  }

  @Test
  @DisplayName("跟进修改：待跟进线索可更改为跟进中并保持未删")
  void update_followUp_setsStatusOne() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 0, OPERATOR_ID));
    when(salesLeadMapper.updateById(any(SalesLeadEntity.class)))
        .thenAnswer(
            inv -> {
              SalesLeadEntity e = inv.getArgument(0);
              e.setStatus(1);
              return 1;
            });

    SalesLeadDTO dto = new SalesLeadDTO();
    dto.setId(100L);
    dto.setStatus(1);
    boolean ok = service.update(dto);

    assertTrue(ok);
    ArgumentCaptor<SalesLeadEntity> captor = ArgumentCaptor.forClass(SalesLeadEntity.class);
    verify(salesLeadMapper).updateById(captor.capture());
    assertEquals(1, captor.getValue().getStatus());
    assertNull(captor.getValue().getIsDeleted()); // 不触碰逻辑删除位
  }

  @Test
  @DisplayName("跟进修改：已转化线索不可修改")
  void update_convertedLead_throws() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 2, OPERATOR_ID));
    SalesLeadDTO dto = new SalesLeadDTO();
    dto.setId(100L);
    dto.setName("改名");
    BaseException ex = assertThrows(BaseException.class, () -> service.update(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("无效关闭：待跟进线索置为3已关闭无效")
  void closeInvalid_setsStatusThree() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 1, OPERATOR_ID));
    when(salesLeadMapper.updateById(any(SalesLeadEntity.class))).thenReturn(1);

    boolean ok = service.closeInvalid(100L);

    assertTrue(ok);
    ArgumentCaptor<SalesLeadEntity> captor = ArgumentCaptor.forClass(SalesLeadEntity.class);
    verify(salesLeadMapper).updateById(captor.capture());
    assertEquals(3, captor.getValue().getStatus());
  }

  @Test
  @DisplayName("无效关闭：已转化线索不可关闭为无效")
  void closeInvalid_convertedLead_throws() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 2, OPERATOR_ID));
    BaseException ex = assertThrows(BaseException.class, () -> service.closeInvalid(100L));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("逻辑删除：待跟进线索置逻辑删除位1")
  void deleteById_happyPath_setsDeletedOne() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 0, OPERATOR_ID));
    when(salesLeadMapper.updateById(any(SalesLeadEntity.class))).thenReturn(1);

    boolean ok = service.deleteById(100L);

    assertTrue(ok);
    ArgumentCaptor<SalesLeadEntity> captor = ArgumentCaptor.forClass(SalesLeadEntity.class);
    verify(salesLeadMapper).updateById(captor.capture());
    assertEquals(1, captor.getValue().getIsDeleted());
  }

  @Test
  @DisplayName("分页查询：按状态与负责人过滤并映射 VO")
  void queryPage_returnsMappedVos() {
    SalesLeadEntity e1 = lead(10L, 0, OPERATOR_ID);
    SalesLeadEntity e2 = lead(11L, 0, OPERATOR_ID);
    Page<SalesLeadEntity> entityPage = new Page<>(1, 10, 2);
    entityPage.setRecords(List.of(e1, e2));
    when(salesLeadMapper.selectPage(any(), any())).thenReturn(entityPage);

    SalesLeadQueryDTO query = new SalesLeadQueryDTO();
    query.setStatus(0);
    query.setOwnerId(OPERATOR_ID);
    Page<SalesLeadVO> voPage = service.queryPage(query, 1, 10);

    assertEquals(2, voPage.getTotal());
    assertEquals(2, voPage.getRecords().size());
    assertEquals("示例科技有限公司", voPage.getRecords().get(0).getCompanyName());
  }

  @Test
  @DisplayName("一键转化：创建客户公司+首要联系人+销售机会，线索回填已转化并关联ID")
  void convert_happyPath_withOpportunity() {
    SalesLeadEntity lead = lead(100L, 1, OPERATOR_ID);
    when(salesLeadMapper.selectById(100L)).thenReturn(lead);
    when(customerCompanyMapper.insert(any(CustomerCompanyEntity.class)))
        .thenAnswer(
            inv -> {
              CustomerCompanyEntity e = inv.getArgument(0);
              e.setId(501L);
              return 1;
            });
    when(customerContactMapper.insert(any(CustomerContactEntity.class)))
        .thenAnswer(
            inv -> {
              CustomerContactEntity e = inv.getArgument(0);
              e.setId(502L);
              return 1;
            });
    when(salesOpportunityMapper.insert(any(SalesOpportunityEntity.class)))
        .thenAnswer(
            inv -> {
              SalesOpportunityEntity e = inv.getArgument(0);
              e.setId(503L);
              return 1;
            });
    when(salesLeadMapper.updateById(any(SalesLeadEntity.class))).thenReturn(1);

    LeadConvertDTO dto = new LeadConvertDTO();
    dto.setLeadId(100L);
    dto.setCreateOpportunity(true);
    dto.setOpportunityName("年度服务采购");
    dto.setEstimatedAmount(BigDecimal.valueOf(88000.00));

    LeadConvertVO vo = service.convert(dto);

    assertEquals(501L, vo.getCompanyId());
    assertEquals(502L, vo.getContactId());
    assertEquals(503L, vo.getOpportunityId());

    // 客户公司字段正确装配
    ArgumentCaptor<CustomerCompanyEntity> company =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyMapper).insert(company.capture());
    assertEquals("示例科技有限公司", company.getValue().getCompanyName());
    assertEquals(Boolean.FALSE, company.getValue().getIsDeleted());
    assertEquals(Integer.valueOf(0), company.getValue().getPoolStatus());

    // 首要联系人归属公司
    ArgumentCaptor<CustomerContactEntity> contact =
        ArgumentCaptor.forClass(CustomerContactEntity.class);
    verify(customerContactMapper).insert(contact.capture());
    assertEquals(501L, contact.getValue().getCompanyId());

    // 商机字段正确装配
    ArgumentCaptor<SalesOpportunityEntity> opp =
        ArgumentCaptor.forClass(SalesOpportunityEntity.class);
    verify(salesOpportunityMapper).insert(opp.capture());
    assertEquals("年度服务采购", opp.getValue().getOpportunityName());
    assertEquals(501L, opp.getValue().getCompanyId());
    assertEquals(502L, opp.getValue().getContactId());
    assertEquals(0, opp.getValue().getStage());

    // 线索回填已转化状态与关联ID
    ArgumentCaptor<SalesLeadEntity> leadUpdate = ArgumentCaptor.forClass(SalesLeadEntity.class);
    verify(salesLeadMapper).updateById(leadUpdate.capture());
    assertEquals(2, leadUpdate.getValue().getStatus());
    assertEquals(501L, leadUpdate.getValue().getConvertedCompanyId());
    assertEquals(502L, leadUpdate.getValue().getConvertedContactId());
    assertEquals(503L, leadUpdate.getValue().getConvertedOpportunityId());
    assertNotNull(leadUpdate.getValue().getConvertedTime());
  }

  @Test
  @DisplayName("一键转化：不建商机时 opportunityId 为空且不调用商机插入")
  void convert_withoutOpportunity_skipInsert() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 0, OPERATOR_ID));
    when(customerCompanyMapper.insert(any(CustomerCompanyEntity.class)))
        .thenAnswer(
            inv -> {
              CustomerCompanyEntity e = inv.getArgument(0);
              e.setId(501L);
              return 1;
            });
    when(customerContactMapper.insert(any(CustomerContactEntity.class)))
        .thenAnswer(
            inv -> {
              CustomerContactEntity e = inv.getArgument(0);
              e.setId(502L);
              return 1;
            });
    when(salesLeadMapper.updateById(any(SalesLeadEntity.class))).thenReturn(1);

    LeadConvertDTO dto = new LeadConvertDTO();
    dto.setLeadId(100L);
    dto.setCreateOpportunity(false);

    LeadConvertVO vo = service.convert(dto);

    assertEquals(501L, vo.getCompanyId());
    assertEquals(502L, vo.getContactId());
    assertNull(vo.getOpportunityId());
    verify(salesOpportunityMapper, never()).insert(any());
  }

  @Test
  @DisplayName("一键转化：已转化线索抛重复转化异常")
  void convert_alreadyConverted_throws() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 2, OPERATOR_ID));
    LeadConvertDTO dto = new LeadConvertDTO();
    dto.setLeadId(100L);
    BaseException ex = assertThrows(BaseException.class, () -> service.convert(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).insert(any());
  }

  @Test
  @DisplayName("一键转化：已关闭无效线索不可转化")
  void convert_invalidLead_throws() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 3, OPERATOR_ID));
    LeadConvertDTO dto = new LeadConvertDTO();
    dto.setLeadId(100L);
    BaseException ex = assertThrows(BaseException.class, () -> service.convert(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).insert(any());
  }

  @Test
  @DisplayName("一键转化：找不到线索抛不存在异常")
  void convert_leadNotFound_throws() {
    when(salesLeadMapper.selectById(anyLong())).thenReturn(null);
    LeadConvertDTO dto = new LeadConvertDTO();
    dto.setLeadId(999L);
    BaseException ex = assertThrows(BaseException.class, () -> service.convert(dto));
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).insert(any());
  }

  @Test
  @DisplayName("逻辑删除：删除不存在的线索抛不存在异常")
  void deleteById_notFound_throws() {
    when(salesLeadMapper.selectById(anyLong())).thenReturn(null);
    BaseException ex = assertThrows(BaseException.class, () -> service.deleteById(999L));
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("跟进修改：找不到线索抛不存在异常且不写库")
  void update_notFound_throwsAndNoWrite() {
    when(salesLeadMapper.selectById(anyLong())).thenReturn(null);
    SalesLeadDTO dto = new SalesLeadDTO();
    dto.setId(999L);
    dto.setName("改名");
    BaseException ex = assertThrows(BaseException.class, () -> service.update(dto));
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), ex.getCode());
    verify(salesLeadMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("查看详情：待跟进线索返回VO")
  void getDetail_happyPath_returnsVo() {
    when(salesLeadMapper.selectById(100L)).thenReturn(lead(100L, 0, OPERATOR_ID));
    SalesLeadVO vo = service.getDetail(100L);
    assertEquals("张三", vo.getName());
    assertEquals(0, vo.getStatus());
    assertEquals("展会", vo.getSource());
  }
}
