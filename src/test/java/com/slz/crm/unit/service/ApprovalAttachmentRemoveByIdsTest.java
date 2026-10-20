package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.impl.ApprovalAttachmentServiceImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 附件按 id 删除的跨模型误删回归（fix-backlog-batch3 任务 4.1/4.3）。
 *
 * <p><b>缺陷</b>：{@code removeByIds} 先按 {@code id IN (ids) AND model_name = ?} 查行，再 {@code
 * deleteBatchIds(ids)} 删<b>原始 ids</b>——调用方把别的模型的附件 id 混进来时，被 select 过滤掉的行照样被物理删掉 （附件表无 {@code
 * is_deleted}，物理删 + 磁盘文件删除都发生在过滤集之外的 id 上）。
 *
 * <p><b>修复口径</b>：只删「过滤后的 entities 的 id」，与同文件 {@code removeSourceAttachmentsByDistinctIds}
 * 的正确形态一致。本类锁定：①混合 id 时删除集等于过滤集；②过滤集为空时零删除调用。
 */
@DisplayName("附件按 id 删除：跨模型 id 不误删")
@ExtendWith(MockitoExtension.class)
class ApprovalAttachmentRemoveByIdsTest {

  @Mock private ApprovalAttachmentMapper approvalAttachmentMapper;

  @InjectMocks private ApprovalAttachmentServiceImpl attachmentService;

  @BeforeEach
  void injectMybatisBaseMapper() {
    // ServiceImpl 的 baseMapper 在父类，@InjectMocks 不注入它
    ReflectionTestUtils.setField(attachmentService, "baseMapper", approvalAttachmentMapper);
  }

  @Test
  @DisplayName("混入他域 id：只删按 model_name 过滤后的 2 行，他域 id 不进 deleteBatchIds")
  void removeByIds_mixedModels_deletesOnlyFilteredRows() {
    ApprovalAttachmentEntity first = entity(11L);
    ApprovalAttachmentEntity third = entity(13L);
    // 12L 属于别的 model_name，select 已把它滤掉
    when(approvalAttachmentMapper.selectList(any())).thenReturn(List.of(first, third));

    assertDoesNotThrow(
        () -> attachmentService.removeByIds(List.of(11L, 12L, 13L), ModelName.CONTACT_TASK));

    assertAll(
        () -> verify(approvalAttachmentMapper).deleteBatchIds(List.of(11L, 13L)),
        // 原始 ids 形态（含他域 12L）绝不允许出现
        () -> verify(approvalAttachmentMapper, never()).deleteBatchIds(List.of(11L, 12L, 13L)));
  }

  @Test
  @DisplayName("过滤后为空：不删任何数据库行")
  void removeByIds_noMatchedRow_deletesNothing() {
    when(approvalAttachmentMapper.selectList(any())).thenReturn(List.of());

    assertDoesNotThrow(
        () -> attachmentService.removeByIds(List.of(21L), ModelName.BUSINESS_ACTIVITY));

    verify(approvalAttachmentMapper, never()).deleteBatchIds(anyCollection());
  }

  private static ApprovalAttachmentEntity entity(Long id) {
    ApprovalAttachmentEntity entity = new ApprovalAttachmentEntity();
    entity.setId(id);
    // filePath 为 null 时 IOUtils.deleteFile 直接跳过磁盘 IO，本单测只验证数据库删除集
    return entity;
  }
}
