package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.vo.ContractExpiringVO;
import com.slz.crm.pojo.vo.ContractOverduePaymentVO;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 合同履约到期预警与逾期应收账款待办联动服务单测（add-contract-warning-and-overdue-reminder 任务 5.1）。
 *
 * <p>覆盖：到期/临期与逾期账款分页查询透传；扫描命中的到期与逾期合同生成正确待办（负责人、类型、优先级、系统指派）； 幂等防重——同公司同负责人同预警类型进行中任务 （status IN
 * (0,1)）已存在时跳过，杜绝重复创建；无候选时零写库。 mock mapper 隔离数据库，杜绝外呼与真库依赖。
 */
@DisplayName("合同预警待办服务")
@ExtendWith(MockitoExtension.class)
class ContractWarningServiceTest {

  @Mock private ContractMapper contractMapper;
  @Mock private ContactTaskMapper contactTaskMapper;

  @InjectMocks private ContractWarningServiceImpl service;

  @Test
  @DisplayName("分页查询到期/临期合同：透传默认窗口 30 天与分页大小")
  void queryExpiringContracts_passesDefaultDaysAndPage() {
    Page<ContractExpiringVO> paged = new Page<>(1, 10);
    when(contractMapper.selectExpiringContracts(any(), eq(30))).thenReturn(paged);
    var result = service.queryExpiringContracts(1, 10, null);
    assertEquals(paged, result);
    verify(contractMapper).selectExpiringContracts(any(), eq(30));
  }

  @Test
  @DisplayName("分页查询逾期账款合同：透传分页大小")
  void queryOverduePaymentContracts_passesPage() {
    Page<ContractOverduePaymentVO> paged = new Page<>(2, 20);
    when(contractMapper.selectOverduePaymentContracts(any())).thenReturn(paged);
    var result = service.queryOverduePaymentContracts(2, 20);
    assertEquals(paged, result);
    verify(contractMapper).selectOverduePaymentContracts(any());
  }

  @Test
  @DisplayName("扫描命中到期与逾期合同：为各负责人生成正确待办")
  void scan_generatesBothKindsOfTasks() {
    ContractExpiringVO expiring1 = expiring(1L, 1001L, "HT-001");
    ContractExpiringVO expiring2 = expiring(2L, 1001L, "HT-002");
    Page<ContractExpiringVO> expPage1 = new Page<>(1, 500);
    expPage1.setRecords(List.of(expiring1, expiring2));
    Page<ContractExpiringVO> expPage2 = new Page<>(2, 500);
    when(contractMapper.selectExpiringContracts(any(), anyInt()))
        .thenReturn(expPage1, expPage2); // 首页有记录、第二页空 → 循环终止

    ContractOverduePaymentVO overdue1 = overdue(3L, 2002L, "HT-101");
    Page<ContractOverduePaymentVO> odPage1 = new Page<>(1, 500);
    odPage1.setRecords(List.of(overdue1));
    Page<ContractOverduePaymentVO> odPage2 = new Page<>(2, 500);
    when(contractMapper.selectOverduePaymentContracts(any())).thenReturn(odPage1, odPage2);

    when(contactTaskMapper.selectCount(any())).thenReturn(0L);
    when(contactTaskMapper.insert(any())).thenReturn(1);

    int created = service.scanAndGenerateWarningTasks();

    assertEquals(3, created);
    ArgumentCaptor<ContactTaskEntity> captor = ArgumentCaptor.forClass(ContactTaskEntity.class);
    verify(contactTaskMapper, times(3)).insert(captor.capture());
    long contractRenew =
        captor.getAllValues().stream().filter(t -> "合同续签".equals(t.getTaskType())).count();
    long overPayment =
        captor.getAllValues().stream().filter(t -> "回款催收".equals(t.getTaskType())).count();
    assertEquals(2, contractRenew);
    assertEquals(1, overPayment);
    // 系统指派 + 高优先级 + 未开始
    assertTrue(captor.getAllValues().stream().allMatch(t -> t.getCreatorId() == 0L));
    assertTrue(captor.getAllValues().stream().allMatch(t -> t.getPriority() == 7));
    assertTrue(captor.getAllValues().stream().allMatch(t -> t.getStatus() == 0));
    assertTrue(captor.getAllValues().stream().anyMatch(t -> t.getAssigneeId() == 1001L));
  }

  @Test
  @DisplayName("幂等防重：同公司同负责人同预警类型存在进行中任务时跳过，不重复创建")
  void scan_skipsWhenInProgressTaskExists() {
    ContractExpiringVO expiring1 = expiring(1L, 1001L, "HT-001");
    Page<ContractExpiringVO> expPage1 = new Page<>(1, 500);
    expPage1.setRecords(List.of(expiring1));
    Page<ContractExpiringVO> expPage2 = new Page<>(2, 500);
    when(contractMapper.selectExpiringContracts(any(), anyInt())).thenReturn(expPage1, expPage2);

    Page<ContractOverduePaymentVO> odPage1 = new Page<>(1, 500);
    Page<ContractOverduePaymentVO> odPage2 = new Page<>(2, 500);
    when(contractMapper.selectOverduePaymentContracts(any())).thenReturn(odPage1, odPage2);

    // 已存在进行中「合同续签」任务 → 该合同跳过，不 insert
    when(contactTaskMapper.selectCount(any())).thenReturn(1L);

    int created = service.scanAndGenerateWarningTasks();

    assertEquals(0, created);
    verify(contactTaskMapper, never()).insert(any());
  }

  @Test
  @DisplayName("无候选：到期与逾期均空，返回 0 且不写库")
  void scan_noCandidates_doesNothing() {
    Page<ContractExpiringVO> expPage1 = new Page<>(1, 500);
    Page<ContractOverduePaymentVO> odPage1 = new Page<>(1, 500);
    when(contractMapper.selectExpiringContracts(any(), anyInt())).thenReturn(expPage1);
    when(contractMapper.selectOverduePaymentContracts(any())).thenReturn(odPage1);

    int created = service.scanAndGenerateWarningTasks();

    assertEquals(0, created);
    verify(contactTaskMapper, never()).selectCount(any());
    verify(contactTaskMapper, never()).insert(any());
  }

  private ContractExpiringVO expiring(Long id, Long ownerId, String contractNo) {
    ContractExpiringVO vo = new ContractExpiringVO();
    vo.setId(id);
    vo.setContractNo(contractNo);
    vo.setContractName("合同-" + id);
    vo.setCompanyId(5000L + id);
    vo.setOwnerId(ownerId);
    vo.setRemainDays(10);
    vo.setTotalAmount(new BigDecimal("10000.00"));
    vo.setPaidAmount(new BigDecimal("4000.00"));
    vo.setRemainingAmount(new BigDecimal("6000.00"));
    return vo;
  }

  private ContractOverduePaymentVO overdue(Long id, Long ownerId, String contractNo) {
    ContractOverduePaymentVO vo = new ContractOverduePaymentVO();
    vo.setId(id);
    vo.setContractNo(contractNo);
    vo.setContractName("合同-" + id);
    vo.setCompanyId(5000L + id);
    vo.setOwnerId(ownerId);
    vo.setRemainDays(-5);
    vo.setTotalAmount(new BigDecimal("20000.00"));
    vo.setPaidAmount(new BigDecimal("8000.00"));
    vo.setRemainingAmount(new BigDecimal("12000.00"));
    return vo;
  }
}
