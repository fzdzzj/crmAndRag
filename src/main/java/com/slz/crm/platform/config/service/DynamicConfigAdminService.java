package com.slz.crm.platform.config.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.ConfigHistoryView;
import com.slz.crm.platform.config.ConfigItemView;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigOperationType;
import com.slz.crm.platform.config.ConfigOperator;
import com.slz.crm.platform.config.ConfigValueType;
import com.slz.crm.platform.config.CurrentUserResolver;
import com.slz.crm.platform.config.DynamicConfigCache;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.audit.DynamicConfigAuditEvent;
import com.slz.crm.platform.config.audit.DynamicConfigAuditRecorder;
import com.slz.crm.platform.config.audit.NoOpDynamicConfigAuditRecorder;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigHistoryMapper;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import com.slz.crm.platform.contract.PlatformErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动态配置管理服务（写操作 + 版本历史/回滚 + 审计；仅超级管理员可用）。
 *
 * <p>职责与护栏（spec-delta「仅超级管理员可写」「配置校验与安全护栏」「配置版本与回滚」）：
 *
 * <ul>
 *   <li><b>越权拒绝</b>：所有管理接口先 {@link #requireSuperAdmin()}——非超管抛 FORBIDDEN(96005)、 未登录抛
 *       UNAUTHORIZED(96003)，且不产生任何配置变更；
 *   <li><b>校验护栏</b>：写前经 {@link DynamicConfigKeyRegistry#validate} 做类型/范围/枚举/长度校验， 非法值抛
 *       VALIDATION(96007) 并保持原值（不落库、不写历史）；
 *   <li><b>版本与回滚</b>：每次变更（含回滚/删除/复活）版本 +1 并追加历史行；回滚目标 = 目标版本行的 new_value，回滚前仍过一遍校验护栏（防历史脏数据）；
 *   <li><b>热生效</b>：任何写操作提交后逐键失效缓存，同实例下一次读取即新值 （跨实例由 {@link DynamicConfigCache} 有界刷新兜底）；
 *   <li><b>审计</b>：变更事件经 {@link DynamicConfigAuditRecorder} 落点（ObjectProvider 可选依赖， D 审计实现合入前落到
 *       {@link NoOpDynamicConfigAuditRecorder} 日志兜底）， 敏感值在事件内掩码，审计不泄明文；
 *   <li><b>并发</b>：写路径带版本乐观锁（WHERE version = 旧版本），冲突抛错提示刷新重试。
 * </ul>
 *
 * <p>线程安全：无状态服务组件；写路径由数据库事务 + 乐观锁保证一致性。
 */
@Slf4j
@Service
public class DynamicConfigAdminService {

  private final DynamicConfigItemMapper itemMapper;
  private final DynamicConfigHistoryMapper historyMapper;
  private final DynamicConfigKeyRegistry registry;
  private final DynamicConfigCache cache;
  private final CurrentUserResolver userResolver;
  private final ObjectProvider<DynamicConfigAuditRecorder> auditRecorders;

  public DynamicConfigAdminService(
      DynamicConfigItemMapper itemMapper,
      DynamicConfigHistoryMapper historyMapper,
      DynamicConfigKeyRegistry registry,
      DynamicConfigCache cache,
      CurrentUserResolver userResolver,
      ObjectProvider<DynamicConfigAuditRecorder> auditRecorders) {
    this.itemMapper = itemMapper;
    this.historyMapper = historyMapper;
    this.registry = registry;
    this.cache = cache;
    this.userResolver = userResolver;
    this.auditRecorders = auditRecorders;
  }

  // ==================== 查询（超管配置中心专属） ====================

  /**
   * 按命名空间列出全部配置项（注册表 + DB 覆盖合并；DB 无覆盖的项展示静态默认）。
   *
   * @param namespace 命名空间；null 或空 = 全部
   */
  public List<ConfigItemView> listItems(String namespace) {
    requireSuperAdmin();
    Map<String, DynamicConfigItemEntity> rowsByKey =
        itemMapper.selectList(null).stream()
            .collect(Collectors.toMap(DynamicConfigItemEntity::getConfigKey, Function.identity()));
    return registry.byNamespace(namespace).stream()
        .map(def -> toView(rowsByKey.get(def.key()), def))
        .toList();
  }

  /**
   * 查询单个配置项详情。
   *
   * @param key 配置键
   */
  public ConfigItemView getItem(String key) {
    requireSuperAdmin();
    ConfigKeyDefinition def = requireDefinition(key);
    return toView(findByKeyAny(key), def);
  }

  /**
   * 查询某配置键的版本历史（按版本倒序）。
   *
   * @param key 配置键
   */
  public List<ConfigHistoryView> history(String key) {
    requireSuperAdmin();
    ConfigKeyDefinition def = requireDefinition(key);
    List<DynamicConfigHistoryEntity> rows =
        historyMapper.selectList(
            new LambdaQueryWrapper<DynamicConfigHistoryEntity>()
                .eq(DynamicConfigHistoryEntity::getConfigKey, key)
                .orderByDesc(DynamicConfigHistoryEntity::getVersion));
    return rows.stream()
        .map(
            h ->
                new ConfigHistoryView(
                    h.getVersion(),
                    h.getOperationType(),
                    mask(h.getOldValue(), def.sensitive()),
                    mask(h.getNewValue(), def.sensitive()),
                    h.getOperatorRef(),
                    h.getRemark(),
                    h.getCreateTime()))
        .toList();
  }

  // ==================== 写操作（仅超管） ====================

  /**
   * 更新（或首次创建/复活）一个配置项的值。
   *
   * <p>校验失败抛 VALIDATION 且不产生任何写入；值与现值相同则幂等成功（不写历史/审计）；
   *
   * @param key 配置键（必须已注册）
   * @param rawValue 原始输入值
   * @param remark 操作备注（可为空）
   * @return 变更后的管理端视图（敏感值已掩码）
   */
  @Transactional(rollbackFor = Exception.class)
  public ConfigItemView updateValue(String key, String rawValue, String remark) {
    ConfigOperator op = requireSuperAdmin();
    ConfigKeyDefinition def = requireDefinition(key);
    ConfigValueType.Parsed parsed = registry.validate(key, rawValue);
    if (!parsed.valid()) {
      // 非法值拒绝：抛校验错误，保持原配置不变（此处尚未触碰数据库）
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), parsed.errorMessage());
    }
    String canonical = parsed.canonical();
    LocalDateTime now = LocalDateTime.now();

    DynamicConfigItemEntity row = findByKeyAny(key);
    ConfigItemView result;
    if (row == null) {
      result = createItem(def, canonical, op, remark, now);
    } else if (Boolean.TRUE.equals(row.getIsDeleted())) {
      result = reviveItem(def, row, canonical, op, remark, now);
    } else {
      result = updateItem(def, row, canonical, op, remark, now);
    }
    return result;
  }

  /**
   * 回滚到指定历史版本（目标值 = 该版本行的 new_value）。
   *
   * @param key 配置键
   * @param targetVersion 目标版本号（须早于当前版本）
   * @param remark 操作备注（为空时自动填“回滚到版本 N”）
   */
  @Transactional(rollbackFor = Exception.class)
  public ConfigItemView rollback(String key, int targetVersion, String remark) {
    ConfigOperator op = requireSuperAdmin();
    ConfigKeyDefinition def = requireDefinition(key);
    DynamicConfigItemEntity row = findByKeyAny(key);
    if (row == null || Boolean.TRUE.equals(row.getIsDeleted())) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "配置不存在或已删除，无法回滚：" + key);
    }
    DynamicConfigHistoryEntity target =
        historyMapper.selectOne(
            new LambdaQueryWrapper<DynamicConfigHistoryEntity>()
                .eq(DynamicConfigHistoryEntity::getConfigKey, key)
                .eq(DynamicConfigHistoryEntity::getVersion, targetVersion)
                .last("LIMIT 1"));
    if (target == null) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "历史版本不存在：" + key + " #" + targetVersion);
    }
    if (targetVersion >= row.getVersion()) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "只能回滚到早于当前版本的版本（当前 v" + row.getVersion() + "）");
    }
    // 回滚目标值仍需过校验护栏：防历史脏数据/旧规则下已非法的值把系统打挂
    ConfigValueType.Parsed parsed = registry.validate(key, target.getNewValue());
    if (!parsed.valid()) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "目标版本值已不再合法，回滚被拒绝：" + parsed.errorMessage());
    }
    String oldValue = row.getConfigValue();
    int newVersion = row.getVersion() + 1;
    int rows =
        itemMapper.update(
            null,
            new LambdaUpdateWrapper<DynamicConfigItemEntity>()
                .eq(DynamicConfigItemEntity::getId, row.getId())
                .eq(DynamicConfigItemEntity::getVersion, row.getVersion())
                .set(DynamicConfigItemEntity::getConfigValue, parsed.canonical())
                .set(DynamicConfigItemEntity::getVersion, newVersion)
                .set(DynamicConfigItemEntity::getUpdatedBy, op.ref())
                .set(DynamicConfigItemEntity::getUpdateTime, now()));
    if (rows == 0) {
      throw new ServiceException(PlatformErrorCode.INTERNAL.getCode(), "配置已被其他会话修改，请刷新后重试");
    }
    row.setConfigValue(parsed.canonical());
    row.setVersion(newVersion);
    row.setUpdatedBy(op.ref());
    row.setUpdateTime(now());
    String rollbackRemark =
        (remark == null || remark.isBlank()) ? "回滚到版本 " + targetVersion : remark;
    appendHistory(
        row.getId(),
        def,
        newVersion,
        ConfigOperationType.ROLLBACK,
        oldValue,
        parsed.canonical(),
        op,
        rollbackRemark);
    recordAudit(
        def, ConfigOperationType.ROLLBACK, oldValue, parsed.canonical(), op, rollbackRemark);
    cache.invalidate(key);
    return toView(row, def);
  }

  /**
   * 软删除配置项 = 恢复静态默认（历史保留，可再次写入复活或回滚）。
   *
   * @param key 配置键
   * @param remark 操作备注（可为空）
   */
  @Transactional(rollbackFor = Exception.class)
  public ConfigItemView deleteOverride(String key, String remark) {
    ConfigOperator op = requireSuperAdmin();
    ConfigKeyDefinition def = requireDefinition(key);
    DynamicConfigItemEntity row = findByKeyAny(key);
    if (row == null || Boolean.TRUE.equals(row.getIsDeleted())) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "配置不存在或已删除（已处于默认态）：" + key);
    }
    String oldValue = row.getConfigValue();
    int newVersion = row.getVersion() + 1;
    int rows =
        itemMapper.update(
            null,
            new LambdaUpdateWrapper<DynamicConfigItemEntity>()
                .eq(DynamicConfigItemEntity::getId, row.getId())
                .eq(DynamicConfigItemEntity::getVersion, row.getVersion())
                .set(DynamicConfigItemEntity::getIsDeleted, true)
                .set(DynamicConfigItemEntity::getVersion, newVersion)
                .set(DynamicConfigItemEntity::getUpdatedBy, op.ref())
                .set(DynamicConfigItemEntity::getUpdateTime, now()));
    if (rows == 0) {
      throw new ServiceException(PlatformErrorCode.INTERNAL.getCode(), "配置已被其他会话修改，请刷新后重试");
    }
    row.setIsDeleted(true);
    row.setVersion(newVersion);
    row.setUpdatedBy(op.ref());
    row.setUpdateTime(now());
    appendHistory(
        row.getId(), def, newVersion, ConfigOperationType.DELETE, oldValue, null, op, remark);
    recordAudit(def, ConfigOperationType.DELETE, oldValue, null, op, remark);
    cache.invalidate(key);
    return toView(row, def);
  }

  /**
   * 手动触发缓存全量刷新（多实例广播失效不可用时的兜底手段）。
   *
   * @return 刷新后缓存条目数
   */
  public int refreshCache() {
    requireSuperAdmin();
    return cache.refreshAll();
  }

  // ==================== 内部实现 ====================

  /** 首次创建（version=1） */
  private ConfigItemView createItem(
      ConfigKeyDefinition def,
      String canonical,
      ConfigOperator op,
      String remark,
      LocalDateTime now) {
    DynamicConfigItemEntity item = new DynamicConfigItemEntity();
    item.setConfigKey(def.key());
    item.setNamespace(def.namespace());
    item.setValueType(def.type().name());
    item.setDescription(def.description());
    item.setSensitive(def.sensitive());
    item.setConfigValue(canonical);
    item.setVersion(1);
    item.setIsDeleted(false);
    item.setCreatedBy(op.ref());
    item.setUpdatedBy(op.ref());
    item.setCreateTime(now);
    item.setUpdateTime(now);
    itemMapper.insert(item);
    appendHistory(item.getId(), def, 1, ConfigOperationType.CREATE, null, canonical, op, remark);
    recordAudit(def, ConfigOperationType.CREATE, null, canonical, op, remark);
    cache.invalidate(def.key());
    return toView(item, def);
  }

  /** 既有项更新（值相同则幂等成功；否则版本 +1 乐观更新） */
  private ConfigItemView updateItem(
      ConfigKeyDefinition def,
      DynamicConfigItemEntity row,
      String canonical,
      ConfigOperator op,
      String remark,
      LocalDateTime now) {
    ConfigItemView result;
    if (Objects.equals(row.getConfigValue(), canonical)) {
      // 幂等：值未变化，不产生历史与审计，直接返回现状
      result = toView(row, def);
    } else {
      String oldValue = row.getConfigValue();
      int newVersion = row.getVersion() + 1;
      int rows =
          itemMapper.update(
              null,
              new LambdaUpdateWrapper<DynamicConfigItemEntity>()
                  .eq(DynamicConfigItemEntity::getId, row.getId())
                  .eq(DynamicConfigItemEntity::getVersion, row.getVersion())
                  .set(DynamicConfigItemEntity::getConfigValue, canonical)
                  .set(DynamicConfigItemEntity::getVersion, newVersion)
                  .set(DynamicConfigItemEntity::getUpdatedBy, op.ref())
                  .set(DynamicConfigItemEntity::getUpdateTime, now));
      if (rows == 0) {
        // 乐观锁冲突：他人并发修改过，拒绝本次写入避免丢更新
        throw new ServiceException(PlatformErrorCode.INTERNAL.getCode(), "配置已被其他会话修改，请刷新后重试");
      }
      row.setConfigValue(canonical);
      row.setVersion(newVersion);
      row.setUpdatedBy(op.ref());
      row.setUpdateTime(now);
      appendHistory(
          row.getId(),
          def,
          newVersion,
          ConfigOperationType.UPDATE,
          oldValue,
          canonical,
          op,
          remark);
      recordAudit(def, ConfigOperationType.UPDATE, oldValue, canonical, op, remark);
      cache.invalidate(def.key());
      result = toView(row, def);
    }
    return result;
  }

  /** 软删后重写 = 复活（同一唯一键，版本保持连续） */
  private ConfigItemView reviveItem(
      ConfigKeyDefinition def,
      DynamicConfigItemEntity row,
      String canonical,
      ConfigOperator op,
      String remark,
      LocalDateTime now) {
    String oldValue = row.getConfigValue();
    int newVersion = row.getVersion() + 1;
    int rows =
        itemMapper.update(
            null,
            new LambdaUpdateWrapper<DynamicConfigItemEntity>()
                .eq(DynamicConfigItemEntity::getId, row.getId())
                .eq(DynamicConfigItemEntity::getVersion, row.getVersion())
                .set(DynamicConfigItemEntity::getIsDeleted, false)
                .set(DynamicConfigItemEntity::getConfigValue, canonical)
                .set(DynamicConfigItemEntity::getVersion, newVersion)
                .set(DynamicConfigItemEntity::getUpdatedBy, op.ref())
                .set(DynamicConfigItemEntity::getUpdateTime, now));
    if (rows == 0) {
      throw new ServiceException(PlatformErrorCode.INTERNAL.getCode(), "配置已被其他会话修改，请刷新后重试");
    }
    row.setIsDeleted(false);
    row.setConfigValue(canonical);
    row.setVersion(newVersion);
    row.setUpdatedBy(op.ref());
    row.setUpdateTime(now);
    appendHistory(
        row.getId(), def, newVersion, ConfigOperationType.REVIVE, oldValue, canonical, op, remark);
    recordAudit(def, ConfigOperationType.REVIVE, oldValue, canonical, op, remark);
    cache.invalidate(def.key());
    return toView(row, def);
  }

  /** 追加一行版本历史（存真实值，回滚需要；展示层再掩码） */
  private void appendHistory(
      Long configId,
      ConfigKeyDefinition def,
      int version,
      ConfigOperationType operation,
      String oldValue,
      String newValue,
      ConfigOperator op,
      String remark) {
    DynamicConfigHistoryEntity history = new DynamicConfigHistoryEntity();
    history.setConfigId(configId);
    history.setConfigKey(def.key());
    history.setValueType(def.type().name());
    history.setVersion(version);
    history.setOperationType(operation.name());
    history.setOldValue(oldValue);
    history.setNewValue(newValue);
    history.setOperatorRef(op.ref());
    history.setRemark(remark);
    history.setCreateTime(now());
    historyMapper.insert(history);
  }

  /** 审计事件（敏感值掩码后交 D 审计流 / 过渡期日志兜底） */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 审计旁路：recorder实现可抛任意运行时，不打断配置主流程
  private void recordAudit(
      ConfigKeyDefinition def,
      ConfigOperationType operation,
      String oldValue,
      String newValue,
      ConfigOperator op,
      String remark) {
    DynamicConfigAuditRecorder recorder =
        auditRecorders.getIfAvailable(NoOpDynamicConfigAuditRecorder::new);
    try {
      recorder.record(
          new DynamicConfigAuditEvent(
              op.ref(),
              op.displayName(),
              def.key(),
              def.namespace(),
              operation.name(),
              mask(oldValue, def.sensitive()),
              mask(newValue, def.sensitive()),
              remark,
              now()));
    } catch (RuntimeException e) {
      // 审计是旁路：实现方异常不得打断配置主流程（规范见 DynamicConfigAuditRecorder 类注释）
      log.warn("动态配置审计记录失败（已降级）：key={}, operation={}", def.key(), operation, e);
    }
  }

  /** 仅超管（roleId=1）可进入管理路径；未登录抛 UNAUTHORIZED，非超管抛 FORBIDDEN */
  private ConfigOperator requireSuperAdmin() {
    ConfigOperator op = userResolver.resolve();
    if (op == null) {
      throw new ServiceException(
          PlatformErrorCode.UNAUTHORIZED.getCode(), PlatformErrorCode.UNAUTHORIZED.getMessage());
    }
    if (!op.isSuperAdmin()) {
      throw new ServiceException(
          PlatformErrorCode.FORBIDDEN.getCode(), PlatformErrorCode.FORBIDDEN.getMessage());
    }
    return op;
  }

  private ConfigKeyDefinition requireDefinition(String key) {
    return registry
        .definitionOf(key)
        .orElseThrow(
            () -> new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "未知配置键：" + key));
  }

  /** 按键查任意行（含软删行；唯一键保证至多一行） */
  private DynamicConfigItemEntity findByKeyAny(String key) {
    return itemMapper.selectOne(
        new LambdaQueryWrapper<DynamicConfigItemEntity>()
            .eq(DynamicConfigItemEntity::getConfigKey, key));
  }

  /** 实体 + 注册表元数据 → 管理端视图（敏感值掩码、软删置空 value） */
  private ConfigItemView toView(DynamicConfigItemEntity row, ConfigKeyDefinition def) {
    boolean deleted = row != null && Boolean.TRUE.equals(row.getIsDeleted());
    String value = (row == null || deleted) ? null : row.getConfigValue();
    return new ConfigItemView(
        def.key(),
        def.namespace(),
        def.type().name(),
        value == null ? null : mask(value, def.sensitive()),
        mask(def.defaultValue(), def.sensitive()),
        def.description(),
        row == null ? null : row.getVersion(),
        row == null ? null : row.getUpdateTime(),
        row == null ? null : row.getUpdatedBy(),
        deleted,
        def.sensitive(),
        def.minValue(),
        def.maxValue(),
        def.allowedValues().isEmpty() ? null : def.allowedValues());
  }

  /** 敏感值掩码：全掩为 {@code ******}，不泄露任何片段（长度也不给，避免辅助爆破）。 空值显示 {@code (空)} 以便与“有值但被掩码”区分。 */
  private String mask(String raw, boolean sensitive) {
    String result;
    if (!sensitive) {
      result = raw;
    } else if (raw == null || raw.isBlank()) {
      result = "(空)";
    } else {
      result = "******";
    }
    return result;
  }

  private LocalDateTime now() {
    return LocalDateTime.now();
  }
}
