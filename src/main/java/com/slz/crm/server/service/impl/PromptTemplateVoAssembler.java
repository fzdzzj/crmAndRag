package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.entity.PromptTemplateEntity;
import com.slz.crm.pojo.entity.PromptTemplateHistoryEntity;
import com.slz.crm.pojo.vo.PromptTemplateHistoryVO;
import com.slz.crm.pojo.vo.PromptTemplateVO;

/**
 * 提示词模板实体 → VO 装配协作类（add-prompt-template-admin 任务 2.3）。
 *
 * <p>纯函数协作：把 {@link PromptTemplateEntity}/{@link PromptTemplateHistoryEntity} 复制为展示 VO， 从 {@link
 * PromptTemplateServiceImpl} 抽出以收敛类规模（与 AssistRequest 拆分同构）。
 */
final class PromptTemplateVoAssembler {

  PromptTemplateVO toVo(PromptTemplateEntity entity) {
    PromptTemplateVO vo = new PromptTemplateVO();
    vo.setId(entity.getId());
    vo.setCode(entity.getCode());
    vo.setName(entity.getName());
    vo.setCategory(entity.getCategory());
    vo.setContent(entity.getContent());
    vo.setVariables(entity.getVariables());
    vo.setVersion(entity.getVersion());
    vo.setStatus(entity.getStatus());
    vo.setRemark(entity.getRemark());
    vo.setCreatedBy(entity.getCreatedBy());
    vo.setCreatedAt(entity.getCreatedAt());
    vo.setUpdatedBy(entity.getUpdatedBy());
    vo.setUpdatedAt(entity.getUpdatedAt());
    return vo;
  }

  PromptTemplateHistoryVO toHistoryVo(PromptTemplateHistoryEntity entity) {
    PromptTemplateHistoryVO vo = new PromptTemplateHistoryVO();
    vo.setId(entity.getId());
    vo.setTemplateId(entity.getTemplateId());
    vo.setVersion(entity.getVersion());
    vo.setContent(entity.getContent());
    vo.setVariables(entity.getVariables());
    vo.setOperation(entity.getOperation());
    vo.setOperator(entity.getOperator());
    vo.setOperatedAt(entity.getOperatedAt());
    return vo;
  }
}
