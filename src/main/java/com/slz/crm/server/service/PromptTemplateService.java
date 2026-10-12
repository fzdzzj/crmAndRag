package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.PromptTemplateDTO;
import com.slz.crm.pojo.dto.PromptTemplateFeedbackDTO;
import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.dto.PromptTemplateRevertDTO;
import com.slz.crm.pojo.vo.PromptTemplateFeedbackVO;
import com.slz.crm.pojo.vo.PromptTemplateHistoryVO;
import com.slz.crm.pojo.vo.PromptTemplateRenderVO;
import com.slz.crm.pojo.vo.PromptTemplateVO;
import java.util.List;

/**
 * 提示词模板服务（add-prompt-template-admin 任务 2.1/2.2 + prompt-template-enhance 任务 2.1/2.2）。
 *
 * <p>提供提示词模板的创建/更新/删除/启停用/分页/详情/历史/渲染/回滚/反馈能力。更新带乐观锁（expectedVersion）；渲染为纯 Java 占位符替换，
 * 零真实模型外呼。回滚（revert）把目标历史版本内容+variables 作为新版本落档（operation=REVERT + source_version 追溯），审计链不破坏。
 */
public interface PromptTemplateService {

  /** 分页查询模板，支持 code/name/category/status 过滤。 */
  Page<PromptTemplateVO> page(
      Integer pageNum, Integer pageSize, String code, String name, String category, Integer status);

  /** 查询模板详情（含正文与变量定义）。 */
  PromptTemplateVO detail(Long id);

  /** 创建模板（version=1，历史落 CREATE）。 */
  Long create(PromptTemplateDTO dto);

  /** 更新模板（乐观锁 + version 递增 + 历史落 UPDATE）。 */
  Boolean update(PromptTemplateDTO dto);

  /** 物理删除模板（同事务先删 history 再删主表）。 */
  Boolean delete(Long id);

  /** 启停用模板（不改 version，历史落 STATUS）。 */
  Boolean changeStatus(Long id, Integer status);

  /** 查询模板版本历史（按版本倒序）。 */
  List<PromptTemplateHistoryVO> history(Long id);

  /** 渲染模板：启用态模板 + required 校验 + 未定义变量拒绝 + {{var}} 占位符替换。 */
  PromptTemplateRenderVO render(PromptTemplateRenderDTO dto);

  /**
   * 回滚模板到指定历史版本（prompt-template-enhance 任务 2.1）。取目标版本 content+variables 作新版本落档：模板当前版本 +1、 history 落
   * operation=REVERT 并记 source_version（被回滚的目标版本号）；乐观锁 expectedVersion 与当前版本不符 → 92001、
   * targetVersion 不存在于该模板 history → 92008。
   *
   * @return 回滚后的最新版本号
   */
  Integer revert(Long id, PromptTemplateRevertDTO dto);

  /**
   * 记录一条模板版本使用反馈（prompt-template-enhance 任务 2.2）：version 须存在于 history，score 越界（<1 或 >5）拒绝，comment
   * ≤512。
   */
  Boolean addFeedback(Long id, PromptTemplateFeedbackDTO dto);

  /** 分页查询模板使用反馈（prompt-template-enhance 任务 2.2），按 created_at 倒序。 */
  Page<PromptTemplateFeedbackVO> listFeedback(Long id, Integer pageNum, Integer pageSize);
}
