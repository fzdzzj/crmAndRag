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
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 联系人批量逻辑删除委托单测（fix-engineering-debt-loop-write-batch 任务 1.3）。
 *
 * <p>锁定 A-6 服务侧：{@code batchLogicalDeleteByIds} 删掉逐 id 自调用 {@code logicalDeleteById} 的循环，改为对 {@link
 * ForeignKeyDeleteUtil#logicalDeleteByCascadeBatch} 的<b>一次</b>委托（全量 ids 原样下沉）；空入参仍抛 {@code
 * PARAM_EMPTY} 且零数据库交互。
 */
@DisplayName("联系人批量逻辑删除委托（A-6 服务层）")
@ExtendWith(MockitoExtension.class)
class CustomerContactBatchLogicalDeleteTest {

  @Mock private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  @InjectMocks private CustomerContactServiceImpl service;

  @Test
  @DisplayName("批量入口：委托恰一次且全量 ids 原样下沉，单条入口零调用")
  void batchLogicalDeleteByIds_delegatesOnce() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(
            CustomerContactEntity.class, List.of(11L, 12L, 13L)))
        .thenReturn(3);

    assertEquals(Integer.valueOf(3), service.batchLogicalDeleteByIds(List.of(11L, 12L, 13L)));

    verify(foreignKeyDeleteUtil, times(1))
        .logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(11L, 12L, 13L));
    // 旧实现的自调用循环会走单条入口 N 次，必须归零
    verify(foreignKeyDeleteUtil, never()).logicalDeleteByCascade(any(), anyLong());
  }

  @Test
  @DisplayName("批量入口：重复 id 也只是一次委托，计数由级联入口按实际行数返回")
  void batchLogicalDeleteByIds_duplicateIds_delegatesOnce() {
    when(foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(
            CustomerContactEntity.class, List.of(11L, 11L)))
        .thenReturn(1);

    assertEquals(Integer.valueOf(1), service.batchLogicalDeleteByIds(List.of(11L, 11L)));

    verify(foreignKeyDeleteUtil, times(1))
        .logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(11L, 11L));
    verify(foreignKeyDeleteUtil, never()).logicalDeleteByCascade(any(), anyLong());
  }

  @Test
  @DisplayName("批量入口：空入参抛 PARAM_EMPTY 且零数据库交互")
  void batchLogicalDeleteByIds_empty_throws() {
    BaseException ex =
        assertThrows(BaseException.class, () -> service.batchLogicalDeleteByIds(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());

    assertThrows(BaseException.class, () -> service.batchLogicalDeleteByIds(null));
    verifyNoInteractions(foreignKeyDeleteUtil);
  }
}
