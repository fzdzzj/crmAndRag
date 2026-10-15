package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.server.schedule.ContractWarningScheduler;
import com.slz.crm.server.service.ContractWarningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 合同履约到期预警与逾期应收账款待办调度单测（add-contract-warning-and-overdue-reminder 任务 5.2）。
 *
 * <p>直接调用与 {@code @Scheduled}（{@code 0 0 3 * * ?}）及手动触发端点同源的 {@code triggerScan()}， 验证其将控制权委托给
 * {@link ContractWarningService#scanAndGenerateWarningTasks()} 并透传新建数量； cron 表达式本身由
 * {@code @Scheduled} 注解声明、由 {@code @EnableScheduling} 注册，不在本单测重复解析。
 */
@DisplayName("合同预警待办调度")
@ExtendWith(MockitoExtension.class)
class ContractWarningSchedulerTest {

  @Mock private ContractWarningService contractWarningService;

  @InjectMocks private ContractWarningScheduler scheduler;

  @Test
  @DisplayName("手动触发：委托服务扫描并透传新建待办数量")
  void triggerScan_delegatesToService() {
    when(contractWarningService.scanAndGenerateWarningTasks()).thenReturn(5);

    int created = scheduler.triggerScan();

    assertEquals(5, created);
    verify(contractWarningService).scanAndGenerateWarningTasks();
  }

  @Test
  @DisplayName("无命中：透传 0")
  void triggerScan_noCandidates_returnsZero() {
    when(contractWarningService.scanAndGenerateWarningTasks()).thenReturn(0);

    int created = scheduler.triggerScan();

    assertEquals(0, created);
    verify(contractWarningService).scanAndGenerateWarningTasks();
  }
}
