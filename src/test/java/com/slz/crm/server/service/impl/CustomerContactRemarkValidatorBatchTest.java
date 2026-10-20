package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E-14 专项：CustomerContactRemarkValidator 的「先全量校验、后单次 insertBatch」形状。
 *
 * <p>锁定四件事：校验失败零写入（旧逐条实现会留下前 N-1 条）、成功路径 {@code insertBatch} 恰一次且逐条 {@code insert}
 * 零调用、实体装饰（contactId/creatorId + BeanUtils 拷贝的业务字段）不变、replaceRemarks 的 delete 前置保留。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("E-14 联系人备注先校验后批量写")
class CustomerContactRemarkValidatorBatchTest {

  private static final Long CONTACT_ID = 11L;
  private static final Long CREATOR_ID = 7L;

  @Mock private CustomerContactRemarkMapper customerContactRemarkMapper;

  @Captor private ArgumentCaptor<List<CustomerContactRemarkEntity>> entitiesCaptor;

  @Test
  @DisplayName("1. 成功路径：insertBatch 恰一次、单条 insert 零调用、装饰 contactId/creatorId 正确")
  void insertRemarksBatchWritesOnceWithDecoration() {
    CustomerContactRemarkValidator.insertRemarks(
        CONTACT_ID, List.of(favor("篮球"), favor("游泳")), CREATOR_ID, customerContactRemarkMapper);

    verify(customerContactRemarkMapper, times(1)).insertBatch(entitiesCaptor.capture());
    List<CustomerContactRemarkEntity> entities = entitiesCaptor.getValue();
    assertEquals(2, entities.size());
    assertEquals(CONTACT_ID, entities.get(0).getContactId());
    assertEquals(CREATOR_ID, entities.get(0).getCreatorId());
    assertEquals(1, entities.get(0).getRemarkType());
    assertEquals("篮球", entities.get(0).getRemarkContent(), "BeanUtils 拷贝的业务字段保留");
    assertNotNull(entities.get(0).getCreateTime(), "create_time 显式填充，等价旧单条 insert 的库默认值");
    verify(customerContactRemarkMapper, never()).insert(any(CustomerContactRemarkEntity.class));
  }

  @Test
  @DisplayName("2. 校验失败：零写入（insertBatch / insert 均未调用）")
  void insertRemarksValidationFailureWritesNothing() {
    List<CustomerContactRemarkDTO> remarks = List.of(favor("篮球"), invalidType());

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () ->
                CustomerContactRemarkValidator.insertRemarks(
                    CONTACT_ID, remarks, CREATOR_ID, customerContactRemarkMapper));

    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), thrown.getCode());
    verify(customerContactRemarkMapper, never()).insertBatch(anyList());
    verify(customerContactRemarkMapper, never()).insert(any(CustomerContactRemarkEntity.class));
  }

  @Test
  @DisplayName("3. replaceRemarks：delete 前置保留，随后一次 insertBatch")
  void replaceRemarksDeletesThenBatchInserts() {
    CustomerContactRemarkValidator.replaceRemarks(
        CONTACT_ID, List.of(favor("篮球")), CREATOR_ID, customerContactRemarkMapper);

    InOrder order = inOrder(customerContactRemarkMapper);
    order.verify(customerContactRemarkMapper).delete(any());
    order.verify(customerContactRemarkMapper).insertBatch(anyList());
    verify(customerContactRemarkMapper, never()).insert(any(CustomerContactRemarkEntity.class));
    verify(customerContactRemarkMapper, times(1)).insertBatch(entitiesCaptor.capture());
    assertEquals(CONTACT_ID, entitiesCaptor.getValue().get(0).getContactId());
  }

  @Test
  @DisplayName("4. replaceRemarks 校验失败：delete 已执行但零插入")
  void replaceRemarksValidationFailureSkipsBatch() {
    List<CustomerContactRemarkDTO> remarks = List.of(favor("篮球"), invalidType());

    assertThrows(
        BaseException.class,
        () ->
            CustomerContactRemarkValidator.replaceRemarks(
                CONTACT_ID, remarks, CREATOR_ID, customerContactRemarkMapper));

    verify(customerContactRemarkMapper, times(1)).delete(any());
    verify(customerContactRemarkMapper, never()).insertBatch(anyList());
  }

  @Test
  @DisplayName("5. null / 空列表：零交互")
  void emptyRemarksTouchNothing() {
    CustomerContactRemarkValidator.insertRemarks(
        CONTACT_ID, null, CREATOR_ID, customerContactRemarkMapper);
    CustomerContactRemarkValidator.insertRemarks(
        CONTACT_ID, List.of(), CREATOR_ID, customerContactRemarkMapper);

    verifyNoInteractions(customerContactRemarkMapper);
  }

  private CustomerContactRemarkDTO favor(String content) {
    CustomerContactRemarkDTO dto = new CustomerContactRemarkDTO();
    dto.setRemarkType(1);
    dto.setRemarkContent(content);
    return dto;
  }

  private CustomerContactRemarkDTO invalidType() {
    CustomerContactRemarkDTO dto = new CustomerContactRemarkDTO();
    dto.setRemarkType(99);
    return dto;
  }
}
