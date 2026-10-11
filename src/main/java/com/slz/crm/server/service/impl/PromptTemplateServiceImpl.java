package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.PromptTemplateDTO;
import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.entity.PromptTemplateEntity;
import com.slz.crm.pojo.entity.PromptTemplateHistoryEntity;
import com.slz.crm.pojo.vo.PromptTemplateHistoryVO;
import com.slz.crm.pojo.vo.PromptTemplateRenderVO;
import com.slz.crm.pojo.vo.PromptTemplateVO;
import com.slz.crm.server.mapper.PromptTemplateHistoryMapper;
import com.slz.crm.server.mapper.PromptTemplateMapper;
import com.slz.crm.server.service.PromptTemplateService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词模板服务实现（add-prompt-template-admin 任务 2.1/2.2）。
 *
 * <p><b>事务口径</b>：写路径（create/update/delete/changeStatus）在公共入口方法上标 {@code @Transactional}， 删除在同事务先删
 * history 再删主表。渲染（render）为只读纯 Java 组装，零真实外呼。<br>
 * <b>乐观锁</b>：update 携带 {@code expectedVersion}，与当前 version 不符即抛版本冲突业务异常，防并发覆盖。<br>
 * <b>版本语义</b>：内容变更（create/update）版本 +1 并落历史；启停用（changeStatus）不改版本、历史落 STATUS。<br>
 * <b>variables 校验</b>：create/update 经 Jackson 解析，坏 JSON 即拒；render 校验 required 缺失与未定义变量。
 */
@Service
public class PromptTemplateServiceImpl
    extends ServiceImpl<PromptTemplateMapper, PromptTemplateEntity>
    implements PromptTemplateService {

  private static final String OP_CREATE = "CREATE";
  private static final String OP_UPDATE = "UPDATE";
  private static final String OP_STATUS = "STATUS";

  @Autowired private PromptTemplateHistoryMapper promptTemplateHistoryMapper;

  @Autowired private ObjectMapper objectMapper;

  private PromptTemplateRenderSupport renderSupport;

  private PromptTemplateVoAssembler voAssembler;

  private PromptTemplateRenderSupport renderSupport() {
    if (renderSupport == null) {
      renderSupport = new PromptTemplateRenderSupport(objectMapper);
    }
    return renderSupport;
  }

  private PromptTemplateVoAssembler voAssembler() {
    if (voAssembler == null) {
      voAssembler = new PromptTemplateVoAssembler();
    }
    return voAssembler;
  }

  // ===== 查询 =====

  @Override
  public Page<PromptTemplateVO> page(
      Integer pageNum,
      Integer pageSize,
      String code,
      String name,
      String category,
      Integer status) {
    if (pageNum == null || pageNum <= 0) {
      pageNum = 1;
    }
    if (pageSize == null || pageSize <= 0) {
      pageSize = 10;
    }
    LambdaQueryWrapper<PromptTemplateEntity> wrapper = new LambdaQueryWrapper<>();
    wrapper
        .like(code != null && !code.isBlank(), PromptTemplateEntity::getCode, code)
        .like(name != null && !name.isBlank(), PromptTemplateEntity::getName, name)
        .eq(category != null && !category.isBlank(), PromptTemplateEntity::getCategory, category)
        .eq(status != null, PromptTemplateEntity::getStatus, status)
        .orderByDesc(PromptTemplateEntity::getId);
    Page<PromptTemplateEntity> entityPage = this.page(new Page<>(pageNum, pageSize), wrapper);
    Page<PromptTemplateVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize());
    voPage.setTotal(entityPage.getTotal());
    voPage.setRecords(entityPage.getRecords().stream().map(voAssembler()::toVo).toList());
    return voPage;
  }

  @Override
  public PromptTemplateVO detail(Long id) {
    PromptTemplateEntity entity = requireById(id);
    return voAssembler().toVo(entity);
  }

  // ===== 写路径 =====

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long create(PromptTemplateDTO dto) {
    dto.validateBase();
    String variables = renderSupport().parseVariablesOrReject(dto.getVariables());
    ensureCodeNotUsed(dto.getCode());
    Long operator = currentUserId();

    PromptTemplateEntity entity = new PromptTemplateEntity();
    bindContentFields(entity, dto, variables);
    entity.setVersion(1);
    entity.setStatus(0);
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    save(entity);

    appendHistory(entity.getId(), 1, entity.getContent(), variables, OP_CREATE, operator);
    return entity.getId();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(PromptTemplateDTO dto) {
    dto.validateBase();
    String variables = renderSupport().parseVariablesOrReject(dto.getVariables());
    PromptTemplateEntity existing = requireById(dto.getId());
    if (dto.getExpectedVersion() == null
        || !dto.getExpectedVersion().equals(existing.getVersion())) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "提示词模板已被他人修改，请刷新后重试");
    }
    ensureCodeNotUsedExcludingSelf(dto.getCode(), existing.getId());

    Long operator = currentUserId();
    int nextVersion = existing.getVersion() + 1;
    bindContentFields(existing, dto, variables);
    existing.setVersion(nextVersion);
    existing.setUpdatedBy(operator);
    existing.setUpdatedAt(LocalDateTime.now());
    updateById(existing);

    appendHistory(
        existing.getId(), nextVersion, existing.getContent(), variables, OP_UPDATE, operator);
    return true;
  }

  /** 绑定正文变更字段（code/name/category/content/variables/remark），create/update 共用。 */
  private void bindContentFields(
      PromptTemplateEntity entity, PromptTemplateDTO dto, String variables) {
    entity.setCode(dto.getCode());
    entity.setName(dto.getName());
    entity.setCategory(dto.getCategory());
    entity.setContent(dto.getContent());
    entity.setVariables(variables);
    entity.setRemark(dto.getRemark());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean delete(Long id) {
    requireById(id);
    promptTemplateHistoryMapper.delete(
        new LambdaQueryWrapper<PromptTemplateHistoryEntity>()
            .eq(PromptTemplateHistoryEntity::getTemplateId, id));
    removeById(id);
    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean changeStatus(Long id, Integer status) {
    if (status == null || (status != 0 && status != 1)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "状态只能为 0（启用）或 1（停用）");
    }
    PromptTemplateEntity existing = requireById(id);
    boolean unchanged = existing.getStatus() != null && existing.getStatus().equals(status);
    Boolean result = Boolean.TRUE;
    if (!unchanged) {
      Long operator = currentUserId();
      existing.setStatus(status);
      existing.setUpdatedBy(operator);
      existing.setUpdatedAt(LocalDateTime.now());
      updateById(existing);
      appendHistory(
          existing.getId(),
          existing.getVersion(),
          existing.getContent(),
          existing.getVariables(),
          OP_STATUS,
          operator);
    }
    return result;
  }

  // ===== 历史 =====

  @Override
  public List<PromptTemplateHistoryVO> history(Long id) {
    requireById(id);
    List<PromptTemplateHistoryEntity> list =
        promptTemplateHistoryMapper.selectList(
            new LambdaQueryWrapper<PromptTemplateHistoryEntity>()
                .eq(PromptTemplateHistoryEntity::getTemplateId, id)
                .orderByDesc(PromptTemplateHistoryEntity::getVersion));
    return list.stream().map(voAssembler()::toHistoryVo).toList();
  }

  // ===== 渲染 =====

  @Override
  public PromptTemplateRenderVO render(PromptTemplateRenderDTO dto) {
    PromptTemplateEntity template = resolveEnabledTemplate(dto);
    Map<String, String> provided = dto.getVariables() == null ? Map.of() : dto.getVariables();
    List<PromptTemplateRenderSupport.PromptTemplateVariable> definitions =
        renderSupport().parseVariableDefinitions(template.getVariables());
    renderSupport().rejectUndefined(definitions, provided);
    renderSupport().rejectMissingRequired(definitions, provided);

    PromptTemplateRenderVO vo = new PromptTemplateRenderVO();
    vo.setContent(renderSupport().replacePlaceholders(template.getContent(), provided));
    vo.setVersion(template.getVersion());
    vo.setCode(template.getCode());
    return vo;
  }

  /** 定位启用态模板：入参/不存在/停用任一异常即抛（单返回）。 */
  private PromptTemplateEntity resolveEnabledTemplate(PromptTemplateRenderDTO dto) {
    PromptTemplateEntity template;
    if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "渲染模板编码 code 不能为空");
    }
    template =
        getOne(
            new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getCode, dto.getCode())
                .last("LIMIT 1"));
    if (template == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "提示词模板不存在");
    }
    if (template.getStatus() == null || template.getStatus() != 0) {
      throw new BaseException(ErrorCode.NOT_ENABLED, "提示词模板已停用");
    }
    return template;
  }

  // ===== 私有工具 =====

  private PromptTemplateEntity requireById(Long id) {
    PromptTemplateEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "提示词模板不存在");
    }
    return entity;
  }

  private void ensureCodeNotUsed(String code) {
    if (count(
            new LambdaQueryWrapper<PromptTemplateEntity>().eq(PromptTemplateEntity::getCode, code))
        > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "提示词模板编码已存在");
    }
  }

  private void ensureCodeNotUsedExcludingSelf(String code, Long selfId) {
    if (count(
            new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getCode, code)
                .ne(PromptTemplateEntity::getId, selfId))
        > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "提示词模板编码已存在");
    }
  }

  private void appendHistory(
      Long templateId,
      Integer version,
      String content,
      String variables,
      String operation,
      Long operator) {
    PromptTemplateHistoryEntity history = new PromptTemplateHistoryEntity();
    history.setTemplateId(templateId);
    history.setVersion(version);
    history.setContent(content);
    history.setVariables(variables);
    history.setOperation(operation);
    history.setOperator(operator);
    history.setOperatedAt(LocalDateTime.now());
    promptTemplateHistoryMapper.insert(history);
  }

  /** 取当前登录用户ID；无登录上下文返回 null（单返回，避免 generic catch）。 */
  private Long currentUserId() {
    RoleAO role = BaseUnit.getCurrentRole();
    return role == null ? null : role.getId();
  }
}
