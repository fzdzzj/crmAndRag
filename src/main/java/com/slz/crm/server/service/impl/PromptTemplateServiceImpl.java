package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
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
import java.util.Set;
import java.util.stream.Collectors;
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
    voPage.setRecords(entityPage.getRecords().stream().map(this::toVo).toList());
    return voPage;
  }

  @Override
  public PromptTemplateVO detail(Long id) {
    PromptTemplateEntity entity = requireById(id);
    return toVo(entity);
  }

  // ===== 写路径 =====

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long create(PromptTemplateDTO dto) {
    dto.validateBase();
    String variables = parseVariablesOrReject(dto.getVariables());
    ensureCodeNotUsed(dto.getCode());
    Long operator = currentUserId();

    PromptTemplateEntity entity = new PromptTemplateEntity();
    entity.setCode(dto.getCode());
    entity.setName(dto.getName());
    entity.setCategory(dto.getCategory());
    entity.setContent(dto.getContent());
    entity.setVariables(variables);
    entity.setVersion(1);
    entity.setStatus(0);
    entity.setRemark(dto.getRemark());
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
    String variables = parseVariablesOrReject(dto.getVariables());
    PromptTemplateEntity existing = requireById(dto.getId());
    if (dto.getExpectedVersion() == null
        || !dto.getExpectedVersion().equals(existing.getVersion())) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "提示词模板已被他人修改，请刷新后重试");
    }
    ensureCodeNotUsedExcludingSelf(dto.getCode(), existing.getId());

    Long operator = currentUserId();
    int nextVersion = existing.getVersion() + 1;

    existing.setCode(dto.getCode());
    existing.setName(dto.getName());
    existing.setCategory(dto.getCategory());
    existing.setContent(dto.getContent());
    existing.setVariables(variables);
    existing.setVersion(nextVersion);
    existing.setRemark(dto.getRemark());
    existing.setUpdatedBy(operator);
    existing.setUpdatedAt(LocalDateTime.now());
    updateById(existing);

    appendHistory(
        existing.getId(), nextVersion, existing.getContent(), variables, OP_UPDATE, operator);
    return true;
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
    if (existing.getStatus() != null && existing.getStatus().equals(status)) {
      // 状态一致视为幂等，直接返回，不落历史
      return true;
    }
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
    return true;
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
    return list.stream().map(this::toHistoryVo).toList();
  }

  // ===== 渲染 =====

  @Override
  public PromptTemplateRenderVO render(PromptTemplateRenderDTO dto) {
    if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "渲染模板编码 code 不能为空");
    }
    PromptTemplateEntity template =
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

    // 解析变量定义（坏 JSON 理论上不该存在，防御性拒绝）
    List<PromptTemplateVariable> definitions = parseVariableDefinitions(template.getVariables());
    Map<String, String> provided = dto.getVariables() == null ? Map.of() : dto.getVariables();

    Set<String> definedNames =
        definitions.stream()
            .map(PromptTemplateVariable::getName)
            .filter(n -> n != null && !n.isBlank())
            .collect(Collectors.toSet());

    // 未定义变量提供值 → 拒绝（防静默吞错）
    List<String> undefined =
        provided.keySet().stream().filter(k -> !definedNames.contains(k)).sorted().toList();
    if (!undefined.isEmpty()) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "模板未定义变量：" + String.join(", ", undefined));
    }

    // required 缺失 → 拒绝
    List<String> missing =
        definitions.stream()
            .filter(d -> Boolean.TRUE.equals(d.isRequired()))
            .map(PromptTemplateVariable::getName)
            .filter(n -> provided.get(n) == null || provided.get(n).isBlank())
            .sorted()
            .toList();
    if (!missing.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "必填变量缺失：" + String.join(", ", missing));
    }

    // 纯 Java 占位符替换（不用 MessageFormat，避免格式符歧义）
    String rendered = template.getContent();
    for (Map.Entry<String, String> entry : provided.entrySet()) {
      String value = entry.getValue();
      if (value != null) {
        rendered = rendered.replace("{{" + entry.getKey() + "}}", value);
      }
    }

    PromptTemplateRenderVO vo = new PromptTemplateRenderVO();
    vo.setContent(rendered);
    vo.setVersion(template.getVersion());
    vo.setCode(template.getCode());
    return vo;
  }

  // ===== 私有工具 =====

  private PromptTemplateEntity requireById(Long id) {
    PromptTemplateEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "提示词模板不存在");
    }
    return entity;
  }

  /** create/update 入参加载 variables：null/空放行，非空 JSON 数组坏格式即拒。返回归一化文本。 */
  private String parseVariablesOrReject(String variables) {
    if (variables == null || variables.isBlank()) {
      return null;
    }
    parseVariableDefinitions(variables);
    return variables;
  }

  private List<PromptTemplateVariable> parseVariableDefinitions(String variables) {
    if (variables == null || variables.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(
          variables, new TypeReference<List<PromptTemplateVariable>>() {});
    } catch (Exception e) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "变量定义 variables 必须是合法 JSON 数组");
    }
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

  private Long currentUserId() {
    try {
      return BaseUnit.getCurrentId();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private PromptTemplateVO toVo(PromptTemplateEntity entity) {
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

  private PromptTemplateHistoryVO toHistoryVo(PromptTemplateHistoryEntity entity) {
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

  /** 模板变量定义（variables JSON 数组元素）。 */
  public static class PromptTemplateVariable {
    private String name;
    private Boolean required;
    private String description;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Boolean isRequired() {
      return required;
    }

    public void setRequired(Boolean required) {
      this.required = required;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }
  }
}
