package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.service.KbAdminRetrievalStrategyWriteService;
import com.slz.crm.server.service.KbRetrievalStrategyItem;
import com.slz.crm.server.service.KnowledgeAdminService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理 API（admin 面）。 7 端点，全部 @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900) 读写同权。 复用既有摄取/检索/授权。
 *
 * <p>add-per-kb-retrieval-strategy-override 任务 4 新增 4 端点（策略清单 / 单键覆盖 / 单键回落 / 按版本回滚）， 同样挂
 * {@code @RequirePermission(900)}，写前过 12 键白名单。
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

  private final KnowledgeAdminService knowledgeAdminService;

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
      @RequestBody KnowledgeAdminRetrievalRequest req) {
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
}
