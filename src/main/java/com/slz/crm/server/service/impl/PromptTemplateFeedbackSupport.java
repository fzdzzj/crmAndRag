package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.PromptTemplateFeedbackDTO;
import com.slz.crm.pojo.entity.PromptTemplateFeedbackEntity;
import com.slz.crm.pojo.entity.PromptTemplateHistoryEntity;
import com.slz.crm.pojo.vo.PromptTemplateFeedbackVO;
import com.slz.crm.server.mapper.PromptTemplateFeedbackMapper;
import com.slz.crm.server.mapper.PromptTemplateHistoryMapper;
import java.time.LocalDateTime;

/**
 * 提示词模板使用反馈协作类（prompt-template-enhance 任务 2.2）。
 *
 * <p>负责反馈记录（addFeedback）与分页查询（listFeedback）：version 必须存在于该模板 history（不存在→92008）、score 越界（&lt;1 或
 * &gt;5）拒绝（50002）、comment ≤512（超长→50006）。从 {@link PromptTemplateServiceImpl}
 * 抽出以收敛其类规模与圈复杂度，本协作类无事务、 无 Spring 依赖（与 PromptTemplateRenderSupport 同构）。
 */
final class PromptTemplateFeedbackSupport {

  private static final int SCORE_MIN = 1;
  private static final int SCORE_MAX = 5;
  private static final int COMMENT_MAX_LEN = 512;

  private final PromptTemplateFeedbackMapper feedbackMapper;
  private final PromptTemplateHistoryMapper historyMapper;

  PromptTemplateFeedbackSupport(
      PromptTemplateFeedbackMapper feedbackMapper, PromptTemplateHistoryMapper historyMapper) {
    this.feedbackMapper = feedbackMapper;
    this.historyMapper = historyMapper;
  }

  /** 记录反馈：version 存在于 history + score 1-5 + comment ≤512。 */
  Boolean addFeedback(Long templateId, PromptTemplateFeedbackDTO dto, Long operator) {
    PromptTemplateFeedbackDTO checked = validate(dto, templateId);
    PromptTemplateFeedbackEntity entity = new PromptTemplateFeedbackEntity();
    entity.setTemplateId(templateId);
    entity.setVersion(checked.getVersion());
    entity.setScore(checked.getScore());
    entity.setComment(checked.getComment());
    entity.setOperator(operator);
    entity.setCreatedAt(LocalDateTime.now());
    feedbackMapper.insert(entity);
    return true;
  }

  /** 分页查询模板反馈，按创建时间倒序。 */
  Page<PromptTemplateFeedbackVO> listFeedback(Long templateId, Integer pageNum, Integer pageSize) {
    int pn = (pageNum == null || pageNum <= 0) ? 1 : pageNum;
    int ps = (pageSize == null || pageSize <= 0) ? 10 : pageSize;
    Page<PromptTemplateFeedbackEntity> entityPage =
        feedbackMapper.selectPage(
            new Page<>(pn, ps),
            new LambdaQueryWrapper<PromptTemplateFeedbackEntity>()
                .eq(PromptTemplateFeedbackEntity::getTemplateId, templateId)
                .orderByDesc(PromptTemplateFeedbackEntity::getCreatedAt));
    Page<PromptTemplateFeedbackVO> voPage =
        new Page<>(entityPage.getCurrent(), entityPage.getSize());
    voPage.setTotal(entityPage.getTotal());
    voPage.setRecords(
        entityPage.getRecords().stream().map(PromptTemplateFeedbackSupport::toVo).toList());
    return voPage;
  }

  /** 入参校验：version 必填并在 history、score 1-5、comment ≤512（单返回）。 */
  private PromptTemplateFeedbackDTO validate(PromptTemplateFeedbackDTO dto, Long templateId) {
    if (dto == null || dto.getVersion() == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "feedback 版本 version 不能为空");
    }
    Long count =
        historyMapper.selectCount(
            new LambdaQueryWrapper<PromptTemplateHistoryEntity>()
                .eq(PromptTemplateHistoryEntity::getTemplateId, templateId)
                .eq(PromptTemplateHistoryEntity::getVersion, dto.getVersion()));
    if (count == null || count == 0) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "反馈版本 " + dto.getVersion() + " 不存在于该模板历史");
    }
    Integer score = dto.getScore();
    if (score == null || score < SCORE_MIN || score > SCORE_MAX) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "score 必须在 1-5 之间");
    }
    String comment = dto.getComment();
    if (comment != null && comment.length() > COMMENT_MAX_LEN) {
      throw new BaseException(ErrorCode.PARAM_LENGTH_EXCEEDED, "comment 长度不能超过 512");
    }
    return dto;
  }

  private static PromptTemplateFeedbackVO toVo(PromptTemplateFeedbackEntity entity) {
    PromptTemplateFeedbackVO vo = new PromptTemplateFeedbackVO();
    vo.setId(entity.getId());
    vo.setTemplateId(entity.getTemplateId());
    vo.setVersion(entity.getVersion());
    vo.setScore(entity.getScore());
    vo.setComment(entity.getComment());
    vo.setOperator(entity.getOperator());
    vo.setCreatedAt(entity.getCreatedAt());
    return vo;
  }
}
