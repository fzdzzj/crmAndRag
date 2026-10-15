package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.ai.port.BusinessRetrievalPort;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataScopeService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务数据语义检索服务（add-customer-semantic-retrieval 任务 3.1；add-business-activity-semantic-retrieval 任务
 * 3.1/3.2 多实体扩展）。
 *
 * <p>实现 {@link BusinessRetrievalPort}：**双业务实体**多路召回——客户公司（{@code CUSTOMER_COMPANY}）与商业活动 （{@code
 * BUSINESS_ACTIVITY}）同轮检索，各自独立过权限闸档位（NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL） + 补充召回。档位对齐
 * {@code DataScopeService#getHighestDataScopeLevel} 与页面列表 SQL（{@code
 * DataScopeServiceImpl#addDeptScopeCondition}）的冻结语义：客户面资源类型 {@code customer_company}（owner 锚定
 * owner_id）、商业活动面资源类型 {@code business_activity}（owner 锚定 creator_id，见 {@code
 * ResourceTypeConstant.TABLE_USER_FIELDS}）；向量检索落 {@code crm_entity} 集合（{@link
 * CrmEntityVectorStore}）。
 *
 * <p>权限闸口径（与页面列表 SQL 同源，owner 锚定「页面看得到的检索得到」）：NONE→该实体零外发（不检索）；ALL→仅 entityType
 * filter；SELF/TAGE→ownerUserId=本人；DEPT→ownerDeptId=本部门；DEPT_AND_CHILD→子树逐 deptId union （{@link
 * DataScopeDepartmentResolver}）；TAGE 补充召回对 TAGE/DEPT/DEPT_AND_CHILD 档恒开（对齐 {@code
 * DataScopeServiceImpl#addDeptScopeCondition} 的「部门归属 OR 标签资源 OR 显式共享」超集不变量，DEPT 档不因档位收窄 TAGE
 * 可见范围；SELF 档不补——SQL 层 SELF 无 tage）；共享补充召回对全部非 ALL 档恒开。
 *
 * <p>两实体档位互相独立（各查一次 getHighestDataScopeLevel）：一方 NONE 不影响另一方召回；两方皆 NONE 时零外发空返回 （不 embed 不 search）。
 *
 * <p>档位复核（纵深防御）：SELF/TAGE 档召回结果在合并前逐命中按落库 payload 复判归属（ownerUserId=本人，或 ownerDeptId=本部门
 * 且该实体存在补充召回，或 resourceId 命中 TAGE/共享授权清单）——向量库 filter 语义漂移时不致越权放大。DEPT/DEPT_AND_CHILD 档以
 * ownerDeptId 子树检索自行收口，ALL 档超管直通，两档均不额外复核。
 *
 * <p>安全红线：无权业务数据绝不出现在检索结果；检索链路任何异常按空返回降级，不阻断 AiChat 主答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessRetrievalService implements BusinessRetrievalPort {

  /** 客户公司数据权限资源类型（DataScopeTablePermissions 冻结映射）。 */
  static final String CUSTOMER_RESOURCE_TYPE = "customer_company";

  /** 商业活动数据权限资源类型（DataScopeTablePermissions 冻结映射；SELF 用户字段 creator_id）。 */
  static final String BUSINESS_ACTIVITY_RESOURCE_TYPE = "business_activity";

  /** 向量 payload 的 entityType 键（B4 冻结，Qdrant keyword 等值过滤）。 */
  private static final String ENTITY_TYPE_KEY = "entityType";

  /** 向量 payload 的 resourceId 键（TAGE/共享补充召回逐 id 过滤用）。 */
  private static final String RESOURCE_ID_KEY = "resourceId";

  /** 客户向量 payload 的展示补充键（SourceReference.filename=company_name 所需）。 */
  private static final String COMPANY_NAME_KEY = "companyName";

  /** 商业活动向量 payload 的展示补充键（SourceReference.filename=活动标题所需）。 */
  private static final String TITLE_KEY = "title";

  /** 主 filter 的 owner 维度键。 */
  private static final String OWNER_USER_ID_KEY = "ownerUserId";

  /** 主 filter 的部门维度键。 */
  private static final String OWNER_DEPT_ID_KEY = "ownerDeptId";

  /** 来源引用 sourceType：客户公司（受控解冻增量）。 */
  private static final String SOURCE_TYPE_CUSTOMER = "CUSTOMER";

  /** 来源引用 sourceType：商业活动（受控解冻增量 2026-10-14）。 */
  private static final String SOURCE_TYPE_BUSINESS_ACTIVITY = "BUSINESS_ACTIVITY";

  /** 注入上下文头部：向模型声明业务片段引用规则（编号接续知识库片段）。 */
  private static final String CONTEXT_HEADER =
      "【业务数据检索】以下是权限范围内的客户与商业活动数据片段；引用承重结论时在句尾使用 [编号]，" + "片段未支撑的内容不要编造来源。\n\n";

  /**
   * 单实体召回装配（任务 3.2）：entityType payload 值 + 数据权限资源类型 + documentId 前缀 + sourceType + filename 展示键。
   */
  private record RetrievalEntity(
      String payloadEntityType,
      String resourceType,
      String documentPrefix,
      String sourceType,
      String filenameKey) {}

  private static final List<RetrievalEntity> ENTITIES =
      List.of(
          new RetrievalEntity(
              CustomerIngestionService.ENTITY_TYPE,
              CUSTOMER_RESOURCE_TYPE,
              "CUSTOMER_COMPANY:",
              SOURCE_TYPE_CUSTOMER,
              COMPANY_NAME_KEY),
          new RetrievalEntity(
              BusinessActivityIngestionService.ENTITY_TYPE,
              BUSINESS_ACTIVITY_RESOURCE_TYPE,
              "BUSINESS_ACTIVITY:",
              SOURCE_TYPE_BUSINESS_ACTIVITY,
              TITLE_KEY));

  private final CrmEntityVectorStore vectorStore;

  private final EmbeddingService embeddingService;

  private final DataScopeService dataScopeService;

  private final SysDeptMapper sysDeptMapper;

  private final UserMapper userMapper;

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  // 向量/权限多源外呼，任何异常按零命中降级，不阻断 AiChat 主答链路（卡红线②）
  public BusinessRetrievalResult retrieve(BusinessRetrievalQuery query) {
    BusinessRetrievalResult result;
    if (query == null
        || query.userContext() == null
        || query.query() == null
        || query.query().isBlank()) {
      result = BusinessRetrievalResult.empty();
    } else if (!hasAnyScope(query.userContext())) {
      // 权限闸红线：双实体皆 NONE 级零外发——不 embed 不 search，空返回
      result = BusinessRetrievalResult.empty();
    } else {
      try {
        result = buildResult(searchWithPermissionGate(query), query.numberStart());
      } catch (Exception exception) {
        log.warn("业务数据检索失败，按零命中降级继续生成", exception);
        result = BusinessRetrievalResult.empty();
      }
    }
    return result;
  }

  /** 双实体档位任一方非 NONE 即可外发（逐实体闸门在 searchWithPermissionGate 内独立执行）。 */
  private boolean hasAnyScope(UserContext user) {
    boolean scoped = false;
    for (RetrievalEntity entity : ENTITIES) {
      if (resolveLevel(user, entity.resourceType()) != DataScopeLevel.NONE) {
        scoped = true;
        break;
      }
    }
    return scoped;
  }

  /** 解析用户在指定资源类型上的数据范围档位（未知码值按 NONE 收敛，零外发最保守）。 */
  private DataScopeLevel resolveLevel(UserContext user, String resourceType) {
    return DataScopeLevel.fromCode(
        dataScopeService.getHighestDataScopeLevel(toRoleAO(user), resourceType));
  }

  /**
   * 双实体多路召回（任务 3.1/3.2）：逐实体按档位 filter 检索 + TAGE/共享补充召回 union；合并去重（chunkId 取高分） 后按相似度降序截断 topK。NONE
   * 档实体零检索（既不发 embed 也不发 search）。
   *
   * @param query 检索查询
   * @return 合并排序截断后的命中列表
   */
  private List<VectorSearchHit> searchWithPermissionGate(BusinessRetrievalQuery query) {
    UserContext user = query.userContext();
    DataScopeLevel customerLevel = resolveLevel(user, CUSTOMER_RESOURCE_TYPE);
    DataScopeLevel activityLevel = resolveLevel(user, BUSINESS_ACTIVITY_RESOURCE_TYPE);
    List<VectorSearchHit> hits = new ArrayList<>();
    if (customerLevel != DataScopeLevel.NONE || activityLevel != DataScopeLevel.NONE) {
      float[] vector = embeddingService.embed(query.query());
      for (RetrievalEntity entity : ENTITIES) {
        DataScopeLevel level =
            CUSTOMER_RESOURCE_TYPE.equals(entity.resourceType()) ? customerLevel : activityLevel;
        if (level != DataScopeLevel.NONE) {
          hits.addAll(searchEntity(query, user, entity, level, vector));
        }
      }
    }
    // 档位复核（纵深防御）：SELF/TAGE 档按落库 payload 复判归属，向量库 filter 语义漂移时不致越权放大
    return dedupeSortTruncate(
        applyScopePostFilter(hits, user, customerLevel, activityLevel), query.topK());
  }

  /**
   * 档位复核（纵深防御，任务 3.1）：仅当客户面或商业活动面处于 SELF/TAGE 档（ownerUserId=本人）时逐命中复判—— ownerUserId=本人
   * 放行；ownerDeptId=本部门 且存在 TAGE/共享补充召回时放行（补充召回语义）；resourceId 命中 TAGE/共享授权清单（补充召回）放行。DEPT /
   * DEPT_AND_CHILD 档比较 ownerDeptId 归属（子树检索，与页面 SQL 同源）， ALL 档零过滤——两类均无需二次外呼。
   *
   * @param hits 主检索 + 补充召回 union 结果
   * @param user 当前用户身份快照
   * @param customerLevel 客户面档位
   * @param activityLevel 商业活动面档位
   * @return 复核后的命中
   */
  private List<VectorSearchHit> applyScopePostFilter(
      List<VectorSearchHit> hits,
      UserContext user,
      DataScopeLevel customerLevel,
      DataScopeLevel activityLevel) {
    List<VectorSearchHit> result = hits;
    if (!hits.isEmpty()
        && (needsOwnerPostFilter(customerLevel) || needsOwnerPostFilter(activityLevel))) {
      List<Long> grantedResourceIds = grantedResourceIds(user, customerLevel, activityLevel);
      result =
          hits.stream()
              .filter(hit -> hit != null && hit.metadata() != null)
              .filter(hit -> isOwnedBy(hit, user) || isGrantedResource(hit, grantedResourceIds))
              .toList();
    }
    return result;
  }

  /** 该档位是否需要 owner 归属复核（SELF/TAGE 以 ownerUserId 承接主过滤）。 */
  private boolean needsOwnerPostFilter(DataScopeLevel level) {
    return level == DataScopeLevel.SELF || level == DataScopeLevel.TAGE;
  }

  /** 补充召回授权清单（仅对 TAGE/DEPT/DEPT_AND_CHILD 档存在 TAGE 召回；共享召回对全部非 ALL 档恒开）。 */
  private List<Long> grantedResourceIds(
      UserContext user, DataScopeLevel customerLevel, DataScopeLevel activityLevel) {
    List<Long> granted = new ArrayList<>();
    for (RetrievalEntity entity : ENTITIES) {
      granted.addAll(
          nullSafe(
              dataScopeService.getSharedResourceIds(
                  user.userId(), user.roleId(), entity.resourceType())));
    }
    if (hasTageSupplement(customerLevel) || hasTageSupplement(activityLevel)) {
      granted.addAll(
          nullSafe(dataScopeService.getTageResourceIds(user.roleId(), CUSTOMER_RESOURCE_TYPE)));
      granted.addAll(
          nullSafe(
              dataScopeService.getTageResourceIds(user.roleId(), BUSINESS_ACTIVITY_RESOURCE_TYPE)));
    }
    return granted;
  }

  /** 该档位是否开启 TAGE 补充召回（对齐页面 SQL 超集不变量）。 */
  private boolean hasTageSupplement(DataScopeLevel level) {
    return level == DataScopeLevel.TAGE
        || level == DataScopeLevel.DEPT
        || level == DataScopeLevel.DEPT_AND_CHILD;
  }

  /** 归属复核：ownerUserId=本人 或 ownerDeptId=本部门（两键皆缺或不等即无权）。 */
  private boolean isOwnedBy(VectorSearchHit hit, UserContext user) {
    Map<String, Object> metadata = hit.metadata();
    boolean owned = false;
    if (user.userId() != null && user.userId().equals(asLong(metadata.get(OWNER_USER_ID_KEY)))) {
      owned = true;
    } else if (user.deptId() != null
        && user.deptId().equals(asLong(metadata.get(OWNER_DEPT_ID_KEY)))) {
      owned = true;
    }
    return owned;
  }

  /** 显式授权复核：命中 resourceId 位于 TAGE/共享授权清单内。 */
  private boolean isGrantedResource(VectorSearchHit hit, List<Long> grantedResourceIds) {
    Long resourceId = asLong(hit.metadata().get(RESOURCE_ID_KEY));
    return resourceId != null && grantedResourceIds.contains(resourceId);
  }

  /** payload 数值兼容转换（Qdrant 侧 Long/Integer 均可能回来）。 */
  private Long asLong(Object value) {
    Long result = null;
    if (value instanceof Number number) {
      result = number.longValue();
    } else if (value != null) {
      try {
        result = Long.valueOf(String.valueOf(value));
      } catch (NumberFormatException ignored) {
        result = null;
      }
    }
    return result;
  }

  private List<Long> nullSafe(List<Long> values) {
    return values == null ? List.of() : values;
  }

  /** 单实体权限闸检索：主检索按档位 filter + TAGE/共享补充召回 union。 */
  private List<VectorSearchHit> searchEntity(
      BusinessRetrievalQuery query,
      UserContext user,
      RetrievalEntity entity,
      DataScopeLevel level,
      float[] vector) {
    List<VectorSearchHit> hits = new ArrayList<>();
    if (level == DataScopeLevel.ALL) {
      hits.addAll(search(query, vector, Map.of(ENTITY_TYPE_KEY, entity.payloadEntityType())));
    } else {
      if (level == DataScopeLevel.SELF || level == DataScopeLevel.TAGE) {
        hits.addAll(search(query, vector, ownerFilter(entity, user.userId())));
      } else if (level == DataScopeLevel.DEPT) {
        hits.addAll(search(query, vector, deptFilter(entity, user.deptId())));
      } else if (level == DataScopeLevel.DEPT_AND_CHILD) {
        for (Long deptId : resolveSubtreeDeptIds(user.deptId())) {
          hits.addAll(search(query, vector, deptFilter(entity, deptId)));
        }
      }
      if (level == DataScopeLevel.TAGE
          || level == DataScopeLevel.DEPT
          || level == DataScopeLevel.DEPT_AND_CHILD) {
        // TAGE 补充召回：TAGE/DEPT/DEPT_AND_CHILD 档恒开——对齐页面列表 SQL「部门归属 OR 标签资源 OR 显式共享」
        // 超集不变量（DataScopeServiceImpl#addDeptScopeCondition）；SELF 不补（SQL 层 SELF 无 tage）
        for (Long resourceId :
            dataScopeService.getTageResourceIds(user.roleId(), entity.resourceType())) {
          hits.addAll(search(query, vector, resourceFilter(entity, resourceId)));
        }
      }
      // 共享补充召回：全部非 ALL 档恒开（镜像 canReadResource shared 判权）
      for (Long resourceId :
          dataScopeService.getSharedResourceIds(
              user.userId(), user.roleId(), entity.resourceType())) {
        hits.addAll(search(query, vector, resourceFilter(entity, resourceId)));
      }
    }
    return hits;
  }

  /**
   * 单路向量检索：filter 走 B4 冻结约定（String→keyword / Long→longValue 等值匹配）； minScore
   * 与知识库同源（RetrievalDefaults.MIN_SCORE），topK 透传调用方配置。
   */
  private List<VectorSearchHit> search(
      BusinessRetrievalQuery query, float[] vector, Map<String, Object> filter) {
    List<VectorSearchHit> hits =
        vectorStore.search(
            new VectorSearchRequest(vector, query.topK(), RetrievalDefaults.MIN_SCORE, filter));
    return hits == null ? List.of() : hits;
  }

  /** DEPT_AND_CHILD 子树解析（含根部门；{@link DataScopeDepartmentResolver} 同包协作类）。 */
  private List<Long> resolveSubtreeDeptIds(Long deptId) {
    return new DataScopeDepartmentResolver(sysDeptMapper, userMapper)
        .resolveDepartmentSubtreeIds(deptId);
  }

  /** 合并去重：同 chunkId 取最高分（merge 语义），相似度降序后截断 topK。 */
  private List<VectorSearchHit> dedupeSortTruncate(List<VectorSearchHit> hits, int topK) {
    Map<String, VectorSearchHit> unique = new LinkedHashMap<>();
    for (VectorSearchHit hit : hits) {
      unique.merge(hit.chunkId(), hit, (a, b) -> a.score() >= b.score() ? a : b);
    }
    return unique.values().stream()
        .sorted((a, b) -> Double.compare(b.score(), a.score()))
        .limit(topK)
        .toList();
  }

  /** 组装面向模型的上下文与来源引用（双实体混合命中统一编号，自 numberStart 接续知识库片段）。 */
  private BusinessRetrievalResult buildResult(List<VectorSearchHit> hits, int numberStart) {
    BusinessRetrievalResult result;
    if (hits.isEmpty()) {
      result = BusinessRetrievalResult.empty();
    } else {
      StringBuilder context = new StringBuilder(CONTEXT_HEADER);
      List<SourceReference> sources = new ArrayList<>();
      int number = numberStart;
      for (VectorSearchHit hit : hits) {
        context.append('[').append(number).append("] ").append(hit.text()).append('\n');
        sources.add(toSourceReference(hit));
        number++;
      }
      result =
          new BusinessRetrievalResult(context.toString().trim(), List.copyOf(sources), hits.size());
    }
    return result;
  }

  /**
   * 来源引用映射（任务 3.2，受控解冻增量）：按命中 payload 的 entityType 判源——客户公司 {@code
   * CUSTOMER}（documentId=客户主键、filename=company_name）；商业活动 {@code
   * BUSINESS_ACTIVITY}（documentId=活动主键、filename=活动标题）。
   */
  private SourceReference toSourceReference(VectorSearchHit hit) {
    Map<String, Object> metadata = hit.metadata();
    RetrievalEntity entity = resolveEntity(metadata);
    Object resourceId = metadata == null ? null : metadata.get(RESOURCE_ID_KEY);
    String documentId =
        resourceId == null
            ? stripDocumentPrefix(entity, hit.documentId())
            : String.valueOf(resourceId);
    Object display = metadata == null ? null : metadata.get(entity.filenameKey());
    String name =
        display == null || String.valueOf(display).isBlank() ? documentId : String.valueOf(display);
    return new SourceReference(
        entity.sourceType(),
        "vector",
        name,
        documentId,
        hit.chunkId(),
        0,
        null,
        null,
        hit.text(),
        hit.score());
  }

  /** 命中 payload 的 entityType 判源；缺失或未知值回退客户公司口径（与既有单一实体行为一致）。 */
  private RetrievalEntity resolveEntity(Map<String, Object> metadata) {
    RetrievalEntity result = ENTITIES.get(0);
    Object entityType = metadata == null ? null : metadata.get(ENTITY_TYPE_KEY);
    if (entityType != null) {
      for (RetrievalEntity entity : ENTITIES) {
        if (entity.payloadEntityType().equals(String.valueOf(entityType))) {
          result = entity;
          break;
        }
      }
    }
    return result;
  }

  private String stripDocumentPrefix(RetrievalEntity entity, String documentId) {
    String result = documentId;
    if (documentId != null && documentId.startsWith(entity.documentPrefix())) {
      result = documentId.substring(entity.documentPrefix().length());
    }
    return result;
  }

  private Map<String, Object> ownerFilter(RetrievalEntity entity, Long userId) {
    Map<String, Object> filter = baseFilter(entity);
    filter.put(OWNER_USER_ID_KEY, userId);
    return filter;
  }

  private Map<String, Object> deptFilter(RetrievalEntity entity, Long deptId) {
    Map<String, Object> filter = baseFilter(entity);
    filter.put(OWNER_DEPT_ID_KEY, deptId);
    return filter;
  }

  private Map<String, Object> resourceFilter(RetrievalEntity entity, Long resourceId) {
    Map<String, Object> filter = baseFilter(entity);
    filter.put(RESOURCE_ID_KEY, resourceId);
    return filter;
  }

  private Map<String, Object> baseFilter(RetrievalEntity entity) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put(ENTITY_TYPE_KEY, entity.payloadEntityType());
    return filter;
  }

  /** 身份快照转数据权限判权入参（getHighestDataScopeLevel 只消费 id/roleId/deptId 三元组）。 */
  private RoleAO toRoleAO(UserContext user) {
    RoleAO role = new RoleAO();
    role.setId(user.userId());
    role.setRoleId(user.roleId());
    role.setDeptId(user.deptId());
    return role;
  }
}
