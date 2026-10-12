package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.PromptTemplateRevertDTO;
import com.slz.crm.pojo.entity.PromptTemplateEntity;
import com.slz.crm.pojo.entity.PromptTemplateHistoryEntity;
import com.slz.crm.server.mapper.PromptTemplateHistoryMapper;
import java.time.LocalDateTime;

/**
 * 提示词模板历史变更协作类（prompt-template-enhance 任务 2.1）。
 *
 * <p>统一历史笔维护与回滚目标解析：{@code append} 追加快照行（{@code sourceVersion} 仅 REVERT 笔非空）；{@code applyRevert}
 * 校验乐观锁（92001）/目标版本存在（92008）/等价回滚（50002）并把目标 content+variables 回填实体（version 置下一版本）。从 {@link
 * PromptTemplateServiceImpl} 抽出以收敛其类规模与圈复杂度，本协作类无事务、无 Spring 依赖（与 PromptTemplateRenderSupport 同构）。
 */
final class PromptTemplateHistorySupport {

  private final PromptTemplateHistoryMapper historyMapper;

  PromptTemplateHistorySupport(PromptTemplateHistoryMapper historyMapper) {
    this.historyMapper = historyMapper;
  }

  /**
   * 校验并回填回滚目标到实体，返回回滚后的新版本号。
   *
   * @return 回滚后的新版本号（当前版本 +1）
   */
  int applyRevert(PromptTemplateEntity existing, PromptTemplateRevertDTO dto) {
    if (dto == null || dto.getTargetVersion() == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "目标版本 targetVersion 不能为空");
    }
    if (dto.getExpectedVersion() == null
        || !dto.getExpectedVersion().equals(existing.getVersion())) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "提示词模板已被他人修改，请刷新后重试");
    }
    Integer targetVersion = dto.getTargetVersion();
    PromptTemplateHistoryEntity target =
        historyMapper.selectOne(
            new LambdaQueryWrapper<PromptTemplateHistoryEntity>()
                .eq(PromptTemplateHistoryEntity::getTemplateId, existing.getId())
                .eq(PromptTemplateHistoryEntity::getVersion, targetVersion)
                .last("LIMIT 1"));
    if (target == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "目标版本 " + targetVersion + " 不存在于该模板历史");
    }
    if (targetVersion.equals(existing.getVersion())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "目标版本与当前版本相同，无需回滚");
    }
    int nextVersion = existing.getVersion() + 1;
    existing.setContent(target.getContent());
    existing.setVariables(target.getVariables());
    existing.setVersion(nextVersion);
    return nextVersion;
  }

  /** 追加历史笔（sourceVersion 为 null）。 */
  void append(
      Long templateId,
      Integer version,
      String content,
      String variables,
      String operation,
      Long operator) {
    append(templateId, version, content, variables, operation, operator, null);
  }

  /** 追加历史笔；sourceVersion 仅 REVERT 笔非空（记录被回滚的目标版本号）。 */
  void append(
      Long templateId,
      Integer version,
      String content,
      String variables,
      String operation,
      Long operator,
      Integer sourceVersion) {
    PromptTemplateHistoryEntity history = new PromptTemplateHistoryEntity();
    history.setTemplateId(templateId);
    history.setVersion(version);
    history.setContent(content);
    history.setVariables(variables);
    history.setOperation(operation);
    history.setOperator(operator);
    history.setSourceVersion(sourceVersion);
    history.setOperatedAt(LocalDateTime.now());
    historyMapper.insert(history);
  }
}
