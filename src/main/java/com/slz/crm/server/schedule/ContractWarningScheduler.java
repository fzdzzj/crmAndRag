package com.slz.crm.server.schedule;

import com.slz.crm.server.service.ContractWarningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 合同履约到期预警与逾期应收账款待办调度（add-contract-warning-and-overdue-reminder 任务 3.1）。
 *
 * <p>每天凌晨 3 点（{@code 0 0 3 * * ?}）自动扫描到期/临期合同与逾期应收账款合同，并为合同负责人生成 {@code ContactTask} 待办。幂等防重由
 * {@link ContractWarningService#scanAndGenerateWarningTasks()} 内部保证 （同合同同预警类型进行中任务存在时跳过）。{@code
 * scheduledScan} 与手动触发端点 {@code /contract/warning/trigger} 同源，便于测试与运维。
 */
@Slf4j
@Component
public class ContractWarningScheduler {

  @Autowired private ContractWarningService contractWarningService;

  /** cron：每天凌晨 03:00（服务器时区）执行一次预警扫描与待办派发。 */
  @Scheduled(cron = "0 0 3 * * ?")
  public void scheduledScan() {
    int created = contractWarningService.scanAndGenerateWarningTasks();
    if (created > 0) {
      log.info("合同预警待办调度完成，本次新生成待办 {} 条", created);
    }
  }

  /** 手动触发一次预警扫描与待办生成（与定时调度同源，供测试与运维）。 */
  public int triggerScan() {
    return contractWarningService.scanAndGenerateWarningTasks();
  }
}
