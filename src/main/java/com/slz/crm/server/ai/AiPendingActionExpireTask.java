package com.slz.crm.server.ai;

import com.slz.crm.server.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AI 待确认操作过期定时任务 每分钟扫描 PENDING 且 expire_time < now 的记录，批量置为 EXPIRED */
@Slf4j
@Component
// test-hygiene 任务 2.1：测试环境隔离，生产默认开（matchIfMissing=true 保证未配置时照常调度）
@ConditionalOnProperty(
    name = "crm.ai.scheduled-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiPendingActionExpireTask {

  @Autowired private PendingActionService pendingActionService;

  /** 每分钟执行一次，将过期的 PENDING 记录置为 EXPIRED（幂等） */
  @Scheduled(fixedRate = 60000)
  public void expireOverdueActions() {
    int count = pendingActionService.expireOverdue();

    if (count > 0) {

      log.info("AI 待确认操作过期处理: {} 条记录置为 EXPIRED", count);
    }
  }
}
