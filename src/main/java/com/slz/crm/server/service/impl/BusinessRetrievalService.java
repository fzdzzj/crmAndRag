package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.embedding.EmbeddingService;
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
 * 客户业务数据语义检索服务（add-customer-semantic-retrieval 任务 3.1）。
 *
 * <p>实现 {@link BusinessRetrievalPort}：权限闸档位（NONE 零外发 / SELF / DEPT / DEPT_AND_CHILD / ALL）+ 补充召回，对齐
 * {@code DataScopeService#getHighestDataScopeLevel} 与页面列表 SQL（{@code
 * DataScopeServiceImpl#addDeptScopeCondition}）的冻结语义（resourceType=customer_company）；向量检索落 {@code
 * crm_entity} 集合（{@link CrmEntityVectorStore}）。
 *
 * <p>权限闸口径（与页面列表 SQL 同源，owner 锚定「页面看得到的检索得到」）：NONE→空返回零外发（不 embed 不 search）；ALL→仅 entityType
 * filter；SELF/TAGE→ownerUserId=本人；DEPT→ownerDeptId=本部门；DEPT_AND_CHILD→子树逐 deptId union（{@link
 * DataScopeDepartmentResolver}）；TAGE 补充召回对 TAGE/DEPT/DEPT_AND_CHILD 档恒开（对齐 {@code
 * DataScopeServiceImpl#addDeptScopeCondition} 的「部门归属 OR 标签资源 OR 显式共享」超集不变量，DEPT 档不因档位收窄 TAGE 可见
 * 范围；SELF 档不补——SQL 层 SELF 无 tage）；共享补充召回对全部非 ALL 档恒开。
 *
 * <p>安全红线：无权 customer 绝不出现在检索结果；检索链路任何异常按空返回降级，不阻断 AiChat 主答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessRetrievalService implements BusinessRetrievalPort {

  /** 数据权限资源类型（DataScopeTablePermissions 冻结映射）。 */
  static final String RESOURCE_TYPE = "customer_company";

  /** 业务向量 documentId 前缀（resourceId metadata 缺失时回退剥前缀取主键）。 */
  private static final String DOCUMENT_PREFIX = "CUSTOMER_COMPANY:";

  /** 向量 payload 的 entityType 键（B4 冻结，Qdrant keyword 等值过滤）。 */
  private static final String ENTITY_TYPE_KEY = "entityType";

  /** 向量 payload 的 resourceId 键（TAGE/共享补充召回逐 id 过滤用）。 */
  private static final String RESOURCE_ID_KEY = "resourceId";

  /** 向量 payload 的展示补充键（SourceReference.filename=company_name 所需）。 */
  private static final String COMPANY_NAME_KEY = "companyName";

  /** 主 filter 的 owner 维度键。 */
  private static final String OWNER_USER_ID_KEY = "ownerUserId";

  /** 主 filter 的部门维度键。 */
  private static final String OWNER_DEPT_ID_KEY = "ownerDeptId";

  /** 注入上下文头部：向模型声明业务片段引用规则（编号接续知识库片段）。 */
  private static final String CONTEXT_HEADER =
      "【业务数据检索】以下是权限范围内的客户数据片段；引用承重结论时在句尾使用 [编号]，" + "片段未支撑的内容不要编造来源。\n\n";

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
    } else {
      DataScopeLevel level =
          DataScopeLevel.fromCode(
              dataScopeService.getHighestDataScopeLevel(
                  toRoleAO(query.userContext()), RESOURCE_TYPE));
      if (level == DataScopeLevel.NONE) {
        // 权限闸红线：NONE 级零外发——不 embed 不 search，空返回
        result = BusinessRetrievalResult.empty();
      } else {
        try {
          result = buildResult(searchWithPermissionGate(query, level), query.numberStart());
        } catch (Exception exception) {
          log.warn("业务数据检索失败，按零命中降级继续生成", exception);
          result = BusinessRetrievalResult.empty();
        }
      }
    }
    return result;
  }

  /** 权限闸检索（任务 3.1）：主检索按档位 filter + TAGE/共享补充召回 union， 合并去重（chunkId 取高分）后按相似度降序截断 topK。 */
  private List<VectorSearchHit> searchWithPermissionGate(
      BusinessRetrievalQuery query, DataScopeLevel level) {
    UserContext user = query.userContext();
    float[] vector = embeddingService.embed(query.query());
    List<VectorSearchHit> hits = new ArrayList<>();
    if (level == DataScopeLevel.ALL) {
      hits.addAll(
          search(query, vector, Map.of(ENTITY_TYPE_KEY, CustomerIngestionService.ENTITY_TYPE)));
    } else {
      if (level == DataScopeLevel.SELF || level == DataScopeLevel.TAGE) {
        hits.addAll(search(query, vector, ownerFilter(user.userId())));
      } else if (level == DataScopeLevel.DEPT) {
        hits.addAll(search(query, vector, deptFilter(user.deptId())));
      } else if (level == DataScopeLevel.DEPT_AND_CHILD) {
        for (Long deptId : resolveSubtreeDeptIds(user.deptId())) {
          hits.addAll(search(query, vector, deptFilter(deptId)));
        }
      }
      if (level == DataScopeLevel.TAGE
          || level == DataScopeLevel.DEPT
          || level == DataScopeLevel.DEPT_AND_CHILD) {
        // TAGE 补充召回：TAGE/DEPT/DEPT_AND_CHILD 档恒开——对齐页面列表 SQL「部门归属 OR 标签资源 OR 显式共享」
        // 超集不变量（DataScopeServiceImpl#addDeptScopeCondition）；SELF 不补（SQL 层 SELF 无 tage）
        for (Long resourceId : dataScopeService.getTageResourceIds(user.roleId(), RESOURCE_TYPE)) {
          hits.addAll(search(query, vector, resourceFilter(resourceId)));
        }
      }
      // 共享补充召回：全部非 ALL 档恒开（镜像 canReadResource shared 判权）
      for (Long resourceId :
          dataScopeService.getSharedResourceIds(user.userId(), user.roleId(), RESOURCE_TYPE)) {
        hits.addAll(search(query, vector, resourceFilter(resourceId)));
      }
    }
    return dedupeSortTruncate(hits, query.topK());
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

  /** 组装面向模型的上下文与来源引用（编号自 numberStart 接续知识库片段）。 */
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

  /** 来源引用映射：sourceType=CUSTOMER（受控解冻增量），documentId=客户主键，filename=company_name。 */
  private SourceReference toSourceReference(VectorSearchHit hit) {
    Map<String, Object> metadata = hit.metadata();
    Object resourceId = metadata.get(RESOURCE_ID_KEY);
    String documentId =
        resourceId == null ? stripDocumentPrefix(hit.documentId()) : String.valueOf(resourceId);
    Object companyName = metadata.get(COMPANY_NAME_KEY);
    String name =
        companyName == null || String.valueOf(companyName).isBlank()
            ? documentId
            : String.valueOf(companyName);
    return new SourceReference(
        "CUSTOMER",
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

  private String stripDocumentPrefix(String documentId) {
    String result = documentId;
    if (documentId != null && documentId.startsWith(DOCUMENT_PREFIX)) {
      result = documentId.substring(DOCUMENT_PREFIX.length());
    }
    return result;
  }

  private Map<String, Object> ownerFilter(Long userId) {
    Map<String, Object> filter = baseFilter();
    filter.put(OWNER_USER_ID_KEY, userId);
    return filter;
  }

  private Map<String, Object> deptFilter(Long deptId) {
    Map<String, Object> filter = baseFilter();
    filter.put(OWNER_DEPT_ID_KEY, deptId);
    return filter;
  }

  private Map<String, Object> resourceFilter(Long resourceId) {
    Map<String, Object> filter = baseFilter();
    filter.put(RESOURCE_ID_KEY, resourceId);
    return filter;
  }

  private Map<String, Object> baseFilter() {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put(ENTITY_TYPE_KEY, CustomerIngestionService.ENTITY_TYPE);
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
