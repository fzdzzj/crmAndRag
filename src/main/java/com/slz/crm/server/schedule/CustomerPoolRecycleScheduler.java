package com.slz.crm.server.schedule;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerPoolLogEntity;
import com.slz.crm.server.constant.CustomerPoolConstant;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerPoolLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客户公海池超时自动回收调度（add-customer-public-sea-pool 任务 3.1）。
 *
 * <p>每天凌晨 2 点扫描私海客户：有跟进记录且上次跟进（last_activity_time）距今超过 30 天、或无跟进且创建（create_time）距今超过 15
 * 天的客户，批量释放回公海（pool_status=1、owner=null、记录释放时间与原因），并为每家插入 AUTO_RECYCLE 流转日志（operator_id=0
 * 系统）。{@code recycleOverdueCustomers} 同时被 {@code /company/pool/recycle/trigger}
 * 手动触发用于测试与运维（本卡零新权限，复用 113）。
 *
 * <p>并发幂等：单机 Spring {@code @Scheduled} + 条件 SQL（只扫私海 overdue 行）天然幂等；扫描后以 id 收敛再逐行更新并插日志。
 */
@Slf4j
@Component
public class CustomerPoolRecycleScheduler {

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private CustomerPoolLogMapper customerPoolLogMapper;

  /** cron：每天凌晨 02:00（服务器时区）执行一次超时自动回收。 */
  @Scheduled(cron = "0 0 2 * * ?")
  public void scheduledRecycle() {
    int recycled = recycleOverdueCustomers();
    if (recycled > 0) {
      log.info("客户公海池超时自动回收完成，共回收私海客户 {} 家", recycled);
    }
  }

  /**
   * 扫描并回收超时未跟进的私海客户，返回本次回收数量。
   *
   * @return 回收的客户公司数量
   */
  public int recycleOverdueCustomers() {
    List<CustomerCompanyEntity> overdue = customerCompanyMapper.selectRecyclableCustomers();
    int recycled = 0;
    for (CustomerCompanyEntity company : overdue) {
      Long previousOwner = company.getOwnerId();
      CustomerCompanyEntity update = new CustomerCompanyEntity();
      update.setId(company.getId());
      update.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PUBLIC);
      update.setOwnerId(null);
      update.setPoolReleaseTime(LocalDateTime.now());
      update.setPoolReleaseReason(CustomerPoolConstant.REASON_AUTO_RECYCLE);
      customerCompanyMapper.updateById(update);

      CustomerPoolLogEntity logEntity = new CustomerPoolLogEntity();
      logEntity.setCompanyId(company.getId());
      logEntity.setOperatorId(CustomerPoolConstant.SYSTEM_OPERATOR_ID);
      logEntity.setActionType(CustomerPoolConstant.ACTION_AUTO_RECYCLE);
      logEntity.setPreviousOwnerId(previousOwner);
      logEntity.setNewOwnerId(null);
      logEntity.setReason(CustomerPoolConstant.REASON_AUTO_RECYCLE);
      customerPoolLogMapper.insert(logEntity);
      recycled++;
    }
    return recycled;
  }
}
