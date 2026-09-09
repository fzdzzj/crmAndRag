package com.slz.crm.server.ai;

import com.slz.crm.server.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 待确认操作过期定时任务
 * 每分钟扫描 PENDING 且 expire_time < now 的记录，批量置为 EXPIRED
 */
@Slf4j

@Component
public class AiPendingActionExpireTask {


    @Autowired
    private PendingActionService pendingActionService;

    /**
     * 每分钟执行一次，将过期的 PENDING 记录置为 EXPIRED（幂等）
     */
    @Scheduled(fixedRate = 60000)
    public void expireOverdueActions() {
        int count = pendingActionService.expireOverdue();

        if (count > 0) {

            log.info("AI 待确认操作过期处理: {} 条记录置为 EXPIRED", count);

        }
    }

}
