package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerPoolLogEntity;
import com.slz.crm.server.constant.CustomerPoolConstant;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerPoolLogMapper;
import com.slz.crm.server.schedule.CustomerPoolRecycleScheduler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 客户公海池超时自动回收调度单测（add-customer-public-sea-pool 任务 6.2）。
 *
 * <p>覆盖：有超时候选时间回收并逐家插 AUTO_RECYCLE 日志（operator_id=0 系统）、无候选时零写、批量回收数量正确。 直接调用 {@code
 * recycleOverdueCustomers()}（与 {@code @Scheduled} 及手动触发端点同源）验证逻辑。
 */
@DisplayName("客户公海池超时自动回收调度")
@ExtendWith(MockitoExtension.class)
class CustomerPoolRecycleSchedulerTest {

  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private CustomerPoolLogMapper customerPoolLogMapper;

  @InjectMocks private CustomerPoolRecycleScheduler scheduler;

  @Test
  @DisplayName("有超时候选：回收为公海态并记录前负责人，插入 AUTO_RECYCLE 日志")
  void recycle_withCandidates_updatesAndLogsAutoRecycle() {
    CustomerCompanyEntity overdue = new CustomerCompanyEntity();
    overdue.setId(8001L);
    overdue.setIsDeleted(false);
    overdue.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    overdue.setOwnerId(2002L);
    when(customerCompanyMapper.selectRecyclableCustomers()).thenReturn(List.of(overdue));
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    int recycled = scheduler.recycleOverdueCustomers();

    assertEquals(1, recycled);

    ArgumentCaptor<CustomerCompanyEntity> update =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyMapper).updateById(update.capture());
    assertEquals(CustomerPoolConstant.POOL_STATUS_PUBLIC, update.getValue().getPoolStatus());
    assertTrue(update.getValue().getOwnerId() == null, "回收后 owner 置空");
    assertEquals(
        CustomerPoolConstant.REASON_AUTO_RECYCLE, update.getValue().getPoolReleaseReason());

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_AUTO_RECYCLE, log.getValue().getActionType());
    assertEquals(CustomerPoolConstant.SYSTEM_OPERATOR_ID, log.getValue().getOperatorId());
    assertEquals(Long.valueOf(2002L), log.getValue().getPreviousOwnerId());
  }

  @Test
  @DisplayName("无超时候选：返回 0 且不做任何写入")
  void recycle_noCandidates_doesNothing() {
    when(customerCompanyMapper.selectRecyclableCustomers()).thenReturn(List.of());

    int recycled = scheduler.recycleOverdueCustomers();

    assertEquals(0, recycled);
    verify(customerCompanyMapper, never()).updateById(any());
    verify(customerPoolLogMapper, never()).insert(any());
  }

  @Test
  @DisplayName("多候选：逐个更新并各插一条日志，回收数量正确")
  void recycle_multipleCandidates_batchRecycles() {
    CustomerCompanyEntity a = new CustomerCompanyEntity();
    a.setId(8001L);
    a.setIsDeleted(false);
    a.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    a.setOwnerId(2002L);
    CustomerCompanyEntity b = new CustomerCompanyEntity();
    b.setId(8002L);
    b.setIsDeleted(false);
    b.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    b.setOwnerId(null);
    when(customerCompanyMapper.selectRecyclableCustomers()).thenReturn(List.of(a, b));
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    int recycled = scheduler.recycleOverdueCustomers();

    assertEquals(2, recycled);
    verify(customerCompanyMapper, times(2)).updateById(any());
    verify(customerPoolLogMapper, times(2)).insert(any());
  }
}
