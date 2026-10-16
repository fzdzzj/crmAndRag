package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.server.schedule.ChurnWarningScheduler;
import com.slz.crm.server.service.CustomerChurnWarningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 客户流失预警待办调度单测（add-customer-churn-warning-and-reactivation 任务 4.1）。
 *
 * <p>直接调用与 {@code @Scheduled}（{@code 0 0 4 * * ?}，与合同预警凌晨 3 点错峰）及手动触发端点同源的 {@code
 * triggerScan()}，验证其委托 {@link CustomerChurnWarningService#scanAndGenerateChurnWarningTasks()}
 * 并透传新建数量； cron 表达式由 {@code @Scheduled} 注解声明、由 {@code @EnableScheduling} 注册，不在本单测重复解析。
 */
@DisplayName("客户流失预警调度")
@ExtendWith(MockitoExtension.class)
class ChurnWarningSchedulerTest {

  @Mock private CustomerChurnWarningService customerChurnWarningService;

  @InjectMocks private ChurnWarningScheduler scheduler;

  @Test
  @DisplayName("手动触发：委托服务扫描并透传新建待办数量")
  void triggerScan_delegatesToService() {
    when(customerChurnWarningService.scanAndGenerateChurnWarningTasks()).thenReturn(3);

    int created = scheduler.triggerScan();

    assertEquals(3, created);
    verify(customerChurnWarningService).scanAndGenerateChurnWarningTasks();
  }

  @Test
  @DisplayName("无命中：透传 0")
  void triggerScan_noCandidates_returnsZero() {
    when(customerChurnWarningService.scanAndGenerateChurnWarningTasks()).thenReturn(0);

    assertEquals(0, scheduler.triggerScan());
    verify(customerChurnWarningService).scanAndGenerateChurnWarningTasks();
  }
}
