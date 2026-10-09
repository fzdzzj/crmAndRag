package com.slz.crm.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.knowledge.entity.KbRetrievalStrategyHistory;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyService;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigValueType;
import com.slz.crm.platform.config.DynamicConfigCache;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.mapper.KbRetrievalStrategyHistoryMapper;
import com.slz.crm.server.mapper.KbRetrievalStrategyMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库级检索策略覆盖写端服务（add-per-kb-retrieval-strategy-override 任务 4.1）。
 *
 * <p>语义：写前过 12 键白名单 + 类型/范围校验；{@code (kb_id, strategy_key)} 活覆盖唯一（软删行复活而非新建）； version 每次变更自增；每写一行
 * history 审计（操作者/旧值/新值/版本）；提交后逐键失效读取缓存。
 *
 * <p>权限：端点层面统一挂 {@code @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900)}，本服务不重复判权。
 */
@Slf4j
@Service
public class KbAdminRetrievalStrategyWriteService {

  private static final String HIST_CREATE = "CREATE";
  private static final String HIST_UPDATE = "UPDATE";
  private static final String HIST_DELETE = "DELETE";
  private static final String HIST_REVIVE = "REVIVE";
  private static final String HIST_ROLLBACK = "ROLLBACK";

  private final KbRetrievalStrategyMapper strategyMapper;
  private final KbRetrievalStrategyHistoryMapper historyMapper;
  private final KbRetrievalStrategyWhitelist whitelist;
  private final KbRetrievalStrategyService cacheService;
  private final DynamicConfigCache dynamicConfigCache;

  public KbAdminRetrievalStrategyWriteService(
      KbRetrievalStrategyMapper strategyMapper,
      KbRetrievalStrategyHistoryMapper historyMapper,
      KbRetrievalStrategyWhitelist whitelist,
      KbRetrievalStrategyService cacheService,
      DynamicConfigCache dynamicConfigCache) {
    this.strategyMapper = strategyMapper;
    this.historyMapper = historyMapper;
    this.whitelist = whitelist;
    this.cacheService = cacheService;
    this.dynamicConfigCache = dynamicConfigCache;
  }

  /**
   * 单库策略清单：12 键白名单全键的生效值 + 来源标注（OVERRIDE/GLOBAL/DEFAULT）。
   *
   * @param kbId 知识库 ID
   */
  public List<KbRetrievalStrategyItem> listEffective(Long kbId) {
    List<KbRetrievalStrategyItem> result = new ArrayList<>();
    for (String key : KbRetrievalStrategyWhitelist.keys()) {
      result.add(resolveEffectiveItem(kbId, key));
    }
    return result;
  }

  /** 单键三来源判定：OVERRIDE（本库合法覆盖）&gt; GLOBAL（全局动态配置）&gt; DEFAULT（注册表默认）。 */
  private KbRetrievalStrategyItem resolveEffectiveItem(Long kbId, String key) {
    ConfigKeyDefinition def = whitelist.definitionOf(key).orElse(null);
    KbRetrievalStrategy override = findLive(kbId, key);
    ConfigValueType.Parsed overrideTyped =
        override == null || override.getConfigValue() == null
            ? null
            : whitelist.validate(key, override.getConfigValue());
    DynamicConfigCache.Entry global = dynamicConfigCache.entryOf(key);
    boolean overrideValid = overrideTyped != null && overrideTyped.valid();
    boolean globalPresent = global != null && global.typedValue() != null;

    String source;
    String effective;
    if (overrideValid) {
      source = KbRetrievalStrategyItem.SOURCE_OVERRIDE;
      effective = override.getConfigValue();
    } else if (globalPresent) {
      source = KbRetrievalStrategyItem.SOURCE_GLOBAL;
      effective = global.rawValue();
    } else {
      source = KbRetrievalStrategyItem.SOURCE_DEFAULT;
      effective = def == null ? null : def.defaultValue();
    }
    return new KbRetrievalStrategyItem(
        key,
        source,
        effective,
        override == null ? null : override.getConfigValue(),
        override == null ? 0 : (override.getVersion() == null ? 0 : override.getVersion()));
  }

  /** PUT 单键覆盖：白名单 + 类型/范围校验；覆盖存在则更新、软删则复活，version +1。 */
  @Transactional
  public KbRetrievalStrategyItem put(Long kbId, String key, String rawValue) {
    ConfigValueType.Parsed parsed = whitelist.validate(key, rawValue);
    if (!parsed.valid()) {
      throw new BaseException(ErrorCode.PARAM_OUT_OF_RANGE, parsed.errorMessage());
    }
    String operator = operatorRef();
    KbRetrievalStrategy existing =
        strategyMapper.selectOne(
            new LambdaQueryWrapper<KbRetrievalStrategy>()
                .eq(KbRetrievalStrategy::getKbId, kbId)
                .eq(KbRetrievalStrategy::getStrategyKey, key));
    String operationType;
    if (existing == null) {
      existing = new KbRetrievalStrategy();
      existing.setKbId(kbId);
      existing.setStrategyKey(key);
      existing.setIsDeleted(false);
      existing.setCreatedBy(operator);
      existing.setCreatedAt(LocalDateTime.now());
      operationType = HIST_CREATE;
    } else {
      operationType = Boolean.TRUE.equals(existing.getIsDeleted()) ? HIST_REVIVE : HIST_UPDATE;
      existing.setIsDeleted(false);
      existing.setUpdatedBy(operator);
      existing.setUpdatedAt(LocalDateTime.now());
    }
    int oldVersion = existing.getVersion() == null ? 1 : existing.getVersion();
    String oldValue = existing.getConfigValue();
    int newVersion = oldVersion + 1;
    existing.setConfigValue(parsed.canonical());
    existing.setVersion(newVersion);
    if (existing.getId() == null) {
      strategyMapper.insert(existing);
    } else {
      strategyMapper.updateById(existing);
    }
    writeHistory(
        kbId,
        key,
        existing.getId(),
        newVersion,
        operationType,
        oldValue,
        parsed.canonical(),
        operator,
        null);
    cacheService.invalidate(kbId, key);
    return new KbRetrievalStrategyItem(
        key,
        KbRetrievalStrategyItem.SOURCE_OVERRIDE,
        parsed.canonical(),
        parsed.canonical(),
        newVersion);
  }

  /** DELETE 单键覆盖：软删回落全局（无活覆盖则返回 false）。 */
  @Transactional
  public boolean delete(Long kbId, String key) {
    KbRetrievalStrategy existing = findLive(kbId, key);
    boolean deleted = false;
    if (existing != null) {
      String operator = operatorRef();
      int oldVersion = existing.getVersion() == null ? 1 : existing.getVersion();
      int newVersion = oldVersion + 1;
      String oldValue = existing.getConfigValue();
      existing.setIsDeleted(true);
      existing.setVersion(newVersion);
      existing.setUpdatedBy(operator);
      existing.setUpdatedAt(LocalDateTime.now());
      strategyMapper.updateById(existing);
      writeHistory(
          kbId, key, existing.getId(), newVersion, HIST_DELETE, oldValue, null, operator, null);
      cacheService.invalidate(kbId, key);
      deleted = true;
    }
    return deleted;
  }

  /** POST 按版本回滚：取该 (kb_id, key, version) 历史行的 new_value 写回当前值（version 再 +1），软删则复活。 */
  @Transactional
  public boolean rollback(Long kbId, String key, int version) {
    KbRetrievalStrategyHistory target =
        historyMapper.selectOne(
            new LambdaQueryWrapper<KbRetrievalStrategyHistory>()
                .eq(KbRetrievalStrategyHistory::getKbId, kbId)
                .eq(KbRetrievalStrategyHistory::getStrategyKey, key)
                .eq(KbRetrievalStrategyHistory::getVersion, version));
    boolean rolled = false;
    if (target != null && target.getNewValue() != null) {
      rolled = applyRollback(kbId, key, version, target.getNewValue());
    }
    return rolled;
  }

  /** 回滚落库：把目标历史值写回当前行（无当前行则新建），version +1 并留痕、失效缓存。 */
  private boolean applyRollback(Long kbId, String key, int version, String newValue) {
    String operator = operatorRef();
    KbRetrievalStrategy current =
        strategyMapper.selectOne(
            new LambdaQueryWrapper<KbRetrievalStrategy>()
                .eq(KbRetrievalStrategy::getKbId, kbId)
                .eq(KbRetrievalStrategy::getStrategyKey, key));
    if (current == null) {
      current = new KbRetrievalStrategy();
      current.setKbId(kbId);
      current.setStrategyKey(key);
      current.setCreatedBy(operator);
      current.setCreatedAt(LocalDateTime.now());
    } else {
      current.setUpdatedBy(operator);
      current.setUpdatedAt(LocalDateTime.now());
    }
    int oldVersion = current.getVersion() == null ? 1 : current.getVersion();
    int newNextVersion = oldVersion + 1;
    String oldValue = current.getConfigValue();
    current.setConfigValue(newValue);
    current.setIsDeleted(false);
    current.setVersion(newNextVersion);
    if (current.getId() == null) {
      strategyMapper.insert(current);
    } else {
      strategyMapper.updateById(current);
    }
    writeHistory(
        kbId,
        key,
        current.getId(),
        newNextVersion,
        HIST_ROLLBACK,
        oldValue,
        newValue,
        operator,
        "rollback to v" + version);
    cacheService.invalidate(kbId, key);
    return true;
  }

  private KbRetrievalStrategy findLive(Long kbId, String key) {
    return strategyMapper.selectOne(
        new LambdaQueryWrapper<KbRetrievalStrategy>()
            .eq(KbRetrievalStrategy::getKbId, kbId)
            .eq(KbRetrievalStrategy::getStrategyKey, key)
            .eq(KbRetrievalStrategy::getIsDeleted, false));
  }

  private void writeHistory(
      Long kbId,
      String key,
      Long strategyId,
      int version,
      String operationType,
      String oldValue,
      String newValue,
      String operatorRef,
      String remark) {
    KbRetrievalStrategyHistory row = new KbRetrievalStrategyHistory();
    row.setKbId(kbId);
    row.setStrategyKey(key);
    row.setStrategyId(strategyId);
    row.setVersion(version);
    row.setOperationType(operationType);
    row.setOldValue(oldValue);
    row.setNewValue(newValue);
    row.setOperatorRef(operatorRef);
    row.setRemark(remark);
    row.setCreatedAt(LocalDateTime.now());
    historyMapper.insert(row);
  }

  /** 当前操作人口径（对齐 KnowledgeAdminService.currentUser）：契约口径优先，回退 BaseUnit 桥。 */
  private String operatorRef() {
    String result = "system";
    UserContext user = UserContextHolder.current();
    if (user != null && user.userId() != null) {
      result = "user:" + user.userId();
    } else {
      RoleAO role = BaseUnit.getCurrentRole();
      if (role != null && role.getId() != null) {
        result = "user:" + role.getId();
      }
    }
    return result;
  }
}
