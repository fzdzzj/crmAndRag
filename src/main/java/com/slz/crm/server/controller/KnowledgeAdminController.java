package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.service.KnowledgeAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理 API（admin 面）。
 * 7 端点，全部 @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900) 读写同权。
 * 复用既有摄取/检索/授权。
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeAdminService knowledgeAdminService;

    @GetMapping("/bases")
    @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
    public Result<List<KnowledgeBaseVO>> listBases() {
        return Result.success(knowledgeAdminService.listBases());
    }

    @GetMapping("/files")
    @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
    public Result<List<KnowledgeFileVO>> listFiles(@RequestParam(value = "kbId", required = false) Long kbId) {
        return Result.success(knowledgeAdminService.listFiles(kbId));
    }

    @PostMapping("/files")
    @RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)
    public Result<DocumentIngestionResult> upload(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("kbId") Long kbId) {
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
    public Result<KnowledgeAdminRetrievalResponse> retrievalTest(@RequestBody KnowledgeAdminRetrievalRequest req) {
        KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);
        return Result.success(resp);
    }
}
