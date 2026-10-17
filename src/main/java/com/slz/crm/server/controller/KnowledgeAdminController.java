package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.knowledge.ingestion.ContactTaskIngestionService;
import com.slz.crm.knowledge.ingestion.ContractIngestionService;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.knowledge.ingestion.SalesOpportunityIngestionService;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.service.KbAdminRetrievalStrategyWriteService;
import com.slz.crm.server.service.KbRetrievalStrategyItem;
import com.slz.crm.server.service.KnowledgeAdminService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理 API（admin 面）。 7 端点，全部 @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900) 读写同权。 复用既有摄取/检索/授权。
 *
 * <p>add-per-kb-retrieval-strategy-override 任务 4 新增 4 端点（策略清单 / 单键覆盖 / 单键回落 / 按版本回滚）， 同样挂
 * {@code @RequirePermission(900)}，写前过 12 键白名单。
 *
 * <p>unify-vector-ops-endpoints 新增统一回埋入口 {@code POST /admin/sync/{entity}} 与 {@code POST
 * /admin/sync/all}，
 * 委托五实体既有回埋服务（customer/contract/contactTask/businessActivity/salesOpportunity），同样挂
 * {@code @RequirePermission(900)}；旧散落端点（各业务 Controller 的 {@code /reingestAll} 与本文的 {@code
 * /customer/reingest}）按向后兼容保留。
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

  private final KnowledgeAdminService knowledgeAdminService;

  private final CustomerIngestionService customerIngestionService;

  private final ContractIngestionService contractIngestionService;

  private final ContactTaskIngestionService contactTaskIngestionService;

  private final BusinessActivityIngestionService businessActivityIngestionService;

  private final SalesOpportunityIngestionService salesOpportunityIngestionService;

  /** per-KB 检索策略写端（字段注入以保持既有单参构造；端点统一挂 900 权限）。 */
  @Autowired(required = false)
  private KbAdminRetrievalStrategyWriteService kbStrategyWriteService;

  @GetMapping("/bases")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<List<KnowledgeBaseVO>> listBases() {
    return Result.success(knowledgeAdminService.listBases());
  }

  @GetMapping("/files")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<List<KnowledgeFileVO>> listFiles(
      @RequestParam(value = "kbId", required = false) Long kbId,
      @RequestParam(value = "pageNum", required = false) Integer pageNum,
      @RequestParam(value = "pageSize", required = false) Integer pageSize,
      @RequestParam(value = "sortOrder", required = false, defaultValue = "desc")
          String sortOrder) {
    return Result.success(knowledgeAdminService.listFiles(kbId, pageNum, pageSize, sortOrder));
  }

  @PostMapping("/files")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<DocumentIngestionResult> upload(
      @RequestParam("file") MultipartFile file, @RequestParam("kbId") Long kbId) {
    DocumentIngestionResult result = knowledgeAdminService.upload(file, kbId);
    return Result.success(result);
  }

  @GetMapping("/files/{id}")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<KnowledgeFileVO> getFile(@PathVariable("id") String id) {
    KnowledgeFileVO vo = knowledgeAdminService.getFile(id);
    return Result.success(vo);
  }

  @DeleteMapping("/files/{id}")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<Boolean> deleteFile(@PathVariable("id") String id) {
    boolean ok = knowledgeAdminService.deleteFile(id);
    return Result.success(ok);
  }

  @PostMapping("/files/{id}/reingest")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<DocumentIngestionResult> reingest(@PathVariable("id") String id) {
    DocumentIngestionResult result = knowledgeAdminService.reingest(id);
    return Result.success(result);
  }

  @PostMapping("/retrieval/test")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<KnowledgeAdminRetrievalResponse> retrievalTest(
      @Valid @RequestBody KnowledgeAdminRetrievalRequest req) {
    KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);
    return Result.success(resp);
  }

  // ---------------- add-per-kb-retrieval-strategy-override 任务 4：per-KB 检索策略覆盖管理面 ----------------
  // 4 端点全部复用 KNOWLEDGE_ADMIN_MANAGE(900)；单库作用域 + 三层合并见 docs/dynamic-config-keys.md。

  /** 策略清单：该库 12 键白名单全键生效值 + 来源标注（override/global/default）。 */
  @GetMapping("/strategies")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<List<KbRetrievalStrategyItem>> listStrategies(@RequestParam("kbId") Long kbId) {
    return Result.success(requiredWriteService().listEffective(kbId));
  }

  /** 单键覆盖（PUT）：白名单 + 类型/范围校验；已存在则更新、软删则复活，version +1。 */
  @PutMapping("/strategies/{key}")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<KbRetrievalStrategyItem> putStrategy(
      @PathVariable("key") String key,
      @RequestParam("kbId") Long kbId,
      @org.springframework.web.bind.annotation.RequestBody String rawValue) {
    return Result.success(requiredWriteService().put(kbId, key, rawValue));
  }

  /** 单键覆盖回落全局（DELETE 软删）。 */
  @DeleteMapping("/strategies/{key}")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<Boolean> deleteStrategy(
      @PathVariable("key") String key, @RequestParam("kbId") Long kbId) {
    boolean deleted = requiredWriteService().delete(kbId, key);
    return Result.success(deleted);
  }

  /** 按版本回滚（POST）：取该 (kbId, key, version) 历史行写回当前值。 */
  @PostMapping("/strategies/{key}/rollback")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<Boolean> rollbackStrategy(
      @PathVariable("key") String key,
      @RequestParam("kbId") Long kbId,
      @RequestParam("version") int version) {
    boolean rolled = requiredWriteService().rollback(kbId, key, version);
    return Result.success(rolled);
  }

  private KbAdminRetrievalStrategyWriteService requiredWriteService() {
    if (kbStrategyWriteService == null) {
      throw new IllegalStateException("per-KB 策略写服务未装配");
    }
    return kbStrategyWriteService;
  }

  // ---------------- add-customer-semantic-retrieval 任务 2.2：客户语义全量回埋 ----------------

  /** 客户语义全量回埋（POST，权限 900 复用先例）：分页游标重嵌 is_deleted=0 全量客户公司， 返回成功/失败计数（单条失败不中断）。 */
  @PostMapping("/customer/reingest")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<CustomerIngestionService.ReingestResult> reingestCustomers() {
    return Result.success(customerIngestionService.reingestAll());
  }

  // ---------------- unify-vector-ops-endpoints：向量全量回埋统一收口 ----------------
  // 五个实体（客户/合同/跟进任务/商业活动/销售商机）的统一运维入口，权限全部复用 KNOWLEDGE_ADMIN_MANAGE(900)；
  // 仅做入口委托，不改各实体回埋服务实现；首次真实调用 /sync/* 属成本敏感操作，须 Owner 显式授权后执行。

  /** 单实体全量回埋统一结果（与各实体 ReingestResult 同构：total/success/failure）。 */
  public record SyncResult(int total, int success, int failure) {}

  /** 统一收口端点支持的同步实体（路径参数校验白名单，wireName 即对外路径段）。 */
  enum SyncEntity {
    CUSTOMER("customer"),
    CONTRACT("contract"),
    CONTACT_TASK("contactTask"),
    BUSINESS_ACTIVITY("businessActivity"),
    SALES_OPPORTUNITY("salesOpportunity");

    private final String wireName;

    SyncEntity(String wireName) {
      this.wireName = wireName;
    }

    /** 按路径参数解析；非法实体返回 null（端点回 400）。 */
    static SyncEntity from(String wireName) {
      SyncEntity matched = null;
      for (SyncEntity entity : values()) {
        if (entity.wireName.equals(wireName)) {
          matched = entity;
          break;
        }
      }
      return matched;
    }
  }

  /** 单实体全量回埋（POST）：entity 属五实体枚举之一，非法值 400；委托对应实体回埋服务，不改实现。 */
  @PostMapping("/admin/sync/{entity}")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<SyncResult> syncEntity(
      @PathVariable("entity") String entity, HttpServletResponse response) {
    SyncEntity resolved = SyncEntity.from(entity);
    Result<SyncResult> result;
    if (resolved == null) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      result =
          Result.error(
              ErrorCode.PARAM_FORMAT_ERROR, "未知同步实体 '" + entity + "'，可选：" + syncEntityNames());
    } else {
      result = Result.success(syncOne(resolved));
    }
    return result;
  }

  /** 五实体全量回埋（POST）：逐个委托，返回实体名 → 结果的映射（键序固定为枚举声明序）。 */
  @PostMapping("/admin/sync/all")
  @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
  public Result<Map<String, SyncResult>> syncAll() {
    Map<String, SyncResult> results = new LinkedHashMap<>();
    for (SyncEntity entity : SyncEntity.values()) {
      results.put(entity.wireName, syncOne(entity));
    }
    return Result.success(results);
  }

  private SyncResult syncOne(SyncEntity entity) {
    return switch (entity) {
      case CUSTOMER -> toSyncResult(customerIngestionService.reingestAll());
      case CONTRACT -> toSyncResult(contractIngestionService.reingestAll());
      case CONTACT_TASK -> toSyncResult(contactTaskIngestionService.reingestAll());
      case BUSINESS_ACTIVITY -> toSyncResult(businessActivityIngestionService.reingestAll());
      case SALES_OPPORTUNITY -> toSyncResult(salesOpportunityIngestionService.reingestAll());
    };
  }

  private static SyncResult toSyncResult(CustomerIngestionService.ReingestResult r) {
    return new SyncResult(r.total(), r.success(), r.failure());
  }

  private static SyncResult toSyncResult(ContractIngestionService.ReingestResult r) {
    return new SyncResult(r.total(), r.success(), r.failure());
  }

  private static SyncResult toSyncResult(ContactTaskIngestionService.ReingestResult r) {
    return new SyncResult(r.total(), r.success(), r.failure());
  }

  private static SyncResult toSyncResult(BusinessActivityIngestionService.ReingestResult r) {
    return new SyncResult(r.total(), r.success(), r.failure());
  }

  private static SyncResult toSyncResult(SalesOpportunityIngestionService.ReingestResult r) {
    return new SyncResult(r.total(), r.success(), r.failure());
  }

  private static String syncEntityNames() {
    return Arrays.stream(SyncEntity.values()).map(e -> e.wireName).collect(Collectors.joining("/"));
  }
}
