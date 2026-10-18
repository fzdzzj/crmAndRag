package com.slz.crm.server.schedule;

import com.slz.crm.server.service.CustomerChurnWarningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客户流失预警待办调度（add-customer-churn-warning-and-reactivation 任务 1.2）。
 *
 * <p>每天凌晨 4 点（{@code 0 0 4 * * ?}）自动扫描私海客户并生成「流失预警」待办，与合同预警（凌晨 3 点）错峰一小时， 避免同一时刻抢占数据库；幂等防重由 {@link
 * CustomerChurnWarningService#scanAndGenerateChurnWarningTasks()} 内部保证。 {@code scheduledScan}
 * 与手动触发端点 {@code POST /customer/churn/warning/scan} 同源，便于测试与运维。
 *
 * <p>单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会并发触发重复派发流失预警待办。
 */
@Slf4j
@Component
public class ChurnWarningScheduler {

  @Autowired private CustomerChurnWarningService customerChurnWarningService;

  /** cron：每天凌晨 04:00（服务器时区）执行一次流失预警扫描与待办派发。 */
  @Scheduled(cron = "0 0 4 * * ?")
  public void scheduledScan() {
    int created = customerChurnWarningService.scanAndGenerateChurnWarningTasks();
    if (created > 0) {
      log.info("客户流失预警待办调度完成，本次新生成待办 {} 条", created);
    }
  }

  /** 手动触发一次流失预警扫描与待办生成（与定时调度同源，供测试与运维）。 */
  public int triggerScan() {
    return customerChurnWarningService.scanAndGenerateChurnWarningTasks();
  }
}
