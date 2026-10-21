package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 客户公司批量逻辑删除单趟化单测（fix-backlog-batch3 任务 3.2）。
 *
 * <p>锁定 ⑩ 的三件事：①{@code batchLogicalDeleteByIds} 不再逐 id 自调用 {@code logicalDeleteById}（旧实现 N 轮级联 + N
 * 条主表 update + N 次 afterCommit 注册），改为对 {@link ForeignKeyDeleteUtil#logicalDeleteByCascadeBatch}
 * 的<b>一次</b>委托（全量 ids 原样下沉）；②空入参/ null 抛 {@code PARAM_EMPTY} 且零数据库交互（新增的前置闸）； ③单条入口 {@code
 * logicalDeleteById} 的既有行为零回归（仍走单条级联，不被批量入口的改造牵连）。
 *
 * <p>向量清理分支在本单测里静默跳过（{@code customerIngestionService}/{@code embeddingTaskExecutor} 未装配）， 逐公司派发的语义由
 * {@code CustomerCompanyServiceImpl} 的既有 IT 覆盖。
 */
@DisplayName("客户公司批量逻辑删除单趟化（⑩ 服务层）")
@ExtendWith(MockitoExtension.class)
class CustomerCompanyBatchLogicalDeleteTest {

  @Mock private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  @InjectMocks private CustomerCompanyServiceImpl service;

  @Test
  @DisplayName("批量入口：委托恰一次且全量 ids 原样下沉，单条入口零调用")
  void batchLogicalDeleteByIds_delegatesOnce() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(
            CustomerCompanyEntity.class, List.of(11L, 12L, 13L)))
        .thenReturn(3);

    assertEquals(Integer.valueOf(3), service.batchLogicalDeleteByIds(List.of(11L, 12L, 13L)));

    verify(foreignKeyDeleteUtil, times(1))
        .logicalDeleteByCascadeBatch(CustomerCompanyEntity.class, List.of(11L, 12L, 13L));
    // 旧实现的 for-id 自调用循环会走单条入口 N 次，必须归零
    verify(foreignKeyDeleteUtil, never()).logicalDeleteByCascade(any(), anyLong());
  }

  @Test
  @DisplayName("批量入口：重复 id 也只是一次委托，计数由级联入口按实际命中行数返回")
  void batchLogicalDeleteByIds_duplicateIds_delegatesOnce() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(
            CustomerCompanyEntity.class, List.of(11L, 11L)))
        .thenReturn(1);

    assertEquals(Integer.valueOf(1), service.batchLogicalDeleteByIds(List.of(11L, 11L)));

    verify(foreignKeyDeleteUtil, times(1))
        .logicalDeleteByCascadeBatch(CustomerCompanyEntity.class, List.of(11L, 11L));
    verify(foreignKeyDeleteUtil, never()).logicalDeleteByCascade(any(), anyLong());
  }

  @Test
  @DisplayName("批量入口：空入参 / null 抛 PARAM_EMPTY 且零数据库交互（新增前置闸）")
  void batchLogicalDeleteByIds_empty_throws() {
    BaseException emptyEx =
        assertThrows(BaseException.class, () -> service.batchLogicalDeleteByIds(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), emptyEx.getCode());

    BaseException nullEx =
        assertThrows(BaseException.class, () -> service.batchLogicalDeleteByIds(null));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), nullEx.getCode());

    verifyNoInteractions(foreignKeyDeleteUtil);
  }

  @Test
  @DisplayName("单条入口回归：logicalDeleteById 仍走单条级联，不误用批量入口")
  void logicalDeleteById_stillUsesSingleEntry() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascade(CustomerCompanyEntity.class, 21L))
        .thenReturn(1);

    assertEquals(Integer.valueOf(1), service.logicalDeleteById(21L));

    verify(foreignKeyDeleteUtil).logicalDeleteByCascade(CustomerCompanyEntity.class, 21L);
    verify(foreignKeyDeleteUtil, never()).logicalDeleteByCascadeBatch(any(), any());
  }
}
