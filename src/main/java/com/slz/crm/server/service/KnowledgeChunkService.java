package com.slz.crm.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.PageValidationUtils;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.vo.KnowledgeChunkVO;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 知识库文档切片（chunk）读取服务。
 *
 * <p>add-source-citation-and-dashboard 任务 1.3：按 documentId 分页读取知识库文档来源原文（供前端文档查看抽屉 渲染 + excerpt
 * 段内高亮定位）。权限双保险：端点已挂 803 AI_CHAT_SESSION，服务内再按 documentId 解析所属 知识库并复用知识库检索授权链（{@link
 * KnowledgeBaseAuthorizationService#authorizedKnowledgeBaseIds}）， 无读取授权直接抛 12002 {@link
 * ErrorCode#PERMISSION_DENIED}，防越权读取未授权库文档。
 */
@Service
public class KnowledgeChunkService {

  /** 业务来源 documentId 前缀（与 {@code CustomerIngestionService}'CUSTOMER_COMPANY:{id}' 同源）；业务源不走本端点。 */
  private static final String CUSTOMER_COMPANY_PREFIX = "CUSTOMER_COMPANY:";

  private final UploadedFileMapper uploadedFileMapper;
  private final DocumentVectorChunkMapper chunkMapper;
  private final KnowledgeBaseAuthorizationService authorizationService;

  public KnowledgeChunkService(
      UploadedFileMapper uploadedFileMapper,
      DocumentVectorChunkMapper chunkMapper,
      KnowledgeBaseAuthorizationService authorizationService) {
    this.uploadedFileMapper = uploadedFileMapper;
    this.chunkMapper = chunkMapper;
    this.authorizationService = authorizationService;
  }

  /**
   * 分页读取指定 documentId 的切片列表（chunkIndex 升序）。
   *
   * @param documentId 知识库文档业务键（uploaded_file.document_id，与 SourceReference.documentId 同源）
   * @param pageNum 页码（null 时默认 1）
   * @param pageSize 页大小（null 时默认 10，上限 {@link PageValidationUtils#MAX_PAGE_SIZE}）
   * @param user 当前登录用户上下文（无权限时用于知识库授权判定）
   * @return 切片分页结果（含 chunkIndex/pageNo/rowIndex/chunkText 等定位字段）
   * @throws BaseException documentId 为空 / 业务源 documentId 返回参数错误，无知识库读取授权返回 12002 {@link
   *     ErrorCode#PERMISSION_DENIED} / 页大小超限时抛出
   */
  public Page<KnowledgeChunkVO> listChunks(
      String documentId, Integer pageNum, Integer pageSize, UserContext user) {
    if (documentId == null || documentId.isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "documentId 不能为空");
    }
    if (documentId.startsWith(CUSTOMER_COMPANY_PREFIX)) {
      // 业务来源文档（客户公司语义切片）不走知识库 chunk 读取端点，直接返回参数错误。
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "业务来源文档不支持按 chunk 查看");
    }
    UploadedFileEntity file =
        uploadedFileMapper.selectOne(
            new QueryWrapper<UploadedFileEntity>()
                .eq("document_id", documentId)
                .eq("is_deleted", false));
    if (file == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "文档不存在");
    }
    String kbId = file.getKnowledgeBase();
    if (kbId == null || kbId.isBlank() || !authorized(user, kbId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权访问该知识库文档");
    }
    int[] pageParams = PageValidationUtils.validateAndFix(pageNum, pageSize);
    QueryWrapper<DocumentVectorChunkEntity> qw = new QueryWrapper<>();
    qw.eq("document_id", documentId)
        .eq("is_deleted", false)
        // 只返回检索单元行（CHILD 或存量 null）：PARENT 父块行不嵌入、无独立引用锚，不参与抽屉来源查看。
        .and(w -> w.isNull("chunk_role").or().eq("chunk_role", "CHILD"))
        .orderByAsc("chunk_index");
    Page<DocumentVectorChunkEntity> chunkPage =
        chunkMapper.selectPage(new Page<>(pageParams[0], pageParams[1]), qw);
    Page<KnowledgeChunkVO> result = new Page<>();
    result.setCurrent(chunkPage.getCurrent());
    result.setSize(chunkPage.getSize());
    result.setTotal(chunkPage.getTotal());
    result.setRecords(toVOs(chunkPage.getRecords()));
    return result;
  }

  /** 校验当前用户对 documentId 所属知识库是否具备读取授权（复用知识库检索授权链，非新语义）。 */
  private boolean authorized(UserContext user, String kbId) {
    boolean authorized = false;
    if (user != null) {
      try {
        List<Long> ids =
            authorizationService.authorizedKnowledgeBaseIds(user, List.of(String.valueOf(kbId)));
        authorized = ids.contains(Long.valueOf(kbId));
      } catch (NumberFormatException ignored) {
        // kbId 非常规数字（异常归属），一律视为无授权，不放大读取面。
      }
    }
    return authorized;
  }

  private List<KnowledgeChunkVO> toVOs(List<DocumentVectorChunkEntity> chunks) {
    return chunks.stream()
        .map(
            c -> {
              KnowledgeChunkVO vo = new KnowledgeChunkVO();
              vo.setChunkId(c.getId());
              vo.setDocumentId(c.getDocumentId());
              vo.setChunkText(c.getChunkText());
              vo.setFilename(c.getFilename());
              vo.setChunkIndex(c.getChunkIndex());
              vo.setPageNo(c.getPageNo());
              vo.setRowIndex(c.getRowIndex());
              return vo;
            })
        .toList();
  }
}
