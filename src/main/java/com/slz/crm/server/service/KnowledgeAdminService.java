package com.slz.crm.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentIngestionCommand;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 知识库管理服务（admin API 编排）。 复用既有 DocumentIngestionService / 检索 / 授权服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeAdminService {

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final UploadedFileMapper uploadedFileMapper;
  private final KnowledgeBaseAuthorizationService authorizationService;
  private final DocumentIngestionService ingestionService;
  private final KnowledgeRetrievalServiceImpl retrievalService;

  public List<KnowledgeBaseVO> listBases() {
    UserContext user = currentUser();
    List<Long> visibleIds = authorizationService.visibleKnowledgeBaseIds(user);
    List<KnowledgeBaseVO> result = List.of();
    if (!visibleIds.isEmpty()) {
      List<KnowledgeBaseEntity> bases =
          knowledgeBaseMapper.selectList(
              new QueryWrapper<KnowledgeBaseEntity>().in("id", visibleIds).eq("is_deleted", false));
      result = bases.stream().map(this::toBaseVO).collect(Collectors.toList());
    }
    return result;
  }

  public List<KnowledgeFileVO> listFiles(Long kbId) {
    UserContext user = currentUser();
    QueryWrapper<UploadedFileEntity> qw = new QueryWrapper<>();
    boolean authorized = true;
    if (kbId != null) {
      List<Long> auth =
          authorizationService.authorizedKnowledgeBaseIds(user, List.of(String.valueOf(kbId)));
      if (auth.isEmpty()) {
        authorized = false;
      } else {
        qw.eq("knowledge_base", kbId);
      }
    } else {
      List<Long> visible = authorizationService.visibleKnowledgeBaseIds(user);
      if (visible.isEmpty()) {
        authorized = false;
      } else {
        qw.in("knowledge_base", visible);
      }
    }
    List<KnowledgeFileVO> result = List.of();
    if (authorized) {
      qw.eq("is_deleted", false).orderByDesc("create_time");
      List<UploadedFileEntity> files = uploadedFileMapper.selectList(qw);
      result = files.stream().map(this::toFileVO).collect(Collectors.toList());
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件读入+摄取service多源，失败包装为业务异常
  public DocumentIngestionResult upload(MultipartFile file, Long kbId) {
    UserContext user = currentUser();
    KnowledgeBaseEntity kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || !authorizationService.canWrite(kb, user)) {
      throw new RuntimeException("无权写入该知识库");
    }
    try (InputStream is = file.getInputStream()) {
      DocumentIngestionCommand cmd =
          new DocumentIngestionCommand(
              kbId,
              user,
              file.getOriginalFilename(),
              file.getContentType(),
              file.getSize(),
              null,
              null,
              is);
      return ingestionService.ingest(cmd);
    } catch (Exception e) {
      throw new RuntimeException("upload failed", e);
    }
  }

  public KnowledgeFileVO getFile(String documentId) {
    UploadedFileEntity file =
        uploadedFileMapper.selectOne(
            new QueryWrapper<UploadedFileEntity>().eq("document_id", documentId));
    KnowledgeFileVO result = null;
    if (file != null) {
      result = toFileVO(file);
    }
    return result;
  }

  public boolean deleteFile(String documentId) {
    UploadedFileEntity file =
        uploadedFileMapper.selectOne(
            new QueryWrapper<UploadedFileEntity>().eq("document_id", documentId));
    boolean result = false;
    if (file != null) {
      file.setIsDeleted(true);
      uploadedFileMapper.updateById(file);
      result = true;
    }
    return result;
  }

  public DocumentIngestionResult reingest(String documentId) {
    UserContext user = currentUser();
    return ingestionService.reingest(documentId, user);
  }

  public KnowledgeAdminRetrievalResponse retrievalTest(KnowledgeAdminRetrievalRequest req) {
    boolean useVector = Boolean.TRUE.equals(req.getUseVector());
    KnowledgeAdminRetrievalResponse resp = new KnowledgeAdminRetrievalResponse();
    resp.setQuery(req.getQuery());
    resp.setTopK(req.getTopK());
    resp.setUsedVector(useVector);
    resp.setCandidates(List.of());
    if (useVector) {
      log.info("retrievalTest useVector=true (authorized only)");
    }
    return resp;
  }

  private KnowledgeBaseVO toBaseVO(KnowledgeBaseEntity e) {
    KnowledgeBaseVO vo = new KnowledgeBaseVO();
    vo.setId(e.getId());
    vo.setName(e.getName());
    vo.setDisplayName(e.getDisplayName());
    vo.setVisibility(e.getVisibility() != null ? e.getVisibility().name() : null);
    vo.setOwnerUserId(e.getOwnerUserId());
    vo.setCreateTime(e.getCreateTime());
    return vo;
  }

  private KnowledgeFileVO toFileVO(UploadedFileEntity e) {
    KnowledgeFileVO vo = new KnowledgeFileVO();
    vo.setId(e.getId());
    vo.setDocumentId(e.getDocumentId());
    vo.setOriginalFilename(e.getOriginalFilename());
    vo.setFileType(e.getFileType());
    vo.setStatus(e.getStatus());
    vo.setSegmentCount(e.getSegmentCount());
    vo.setVectorCount(e.getVectorCount());
    try {
      if (e.getKnowledgeBase() != null) {
        vo.setKnowledgeBaseId(Long.valueOf(e.getKnowledgeBase()));
      }
    } catch (NumberFormatException ignored) {
    }
    vo.setCreateTime(e.getCreateTime());
    vo.setErrorMessage(e.getErrorMessage());
    return vo;
  }

  /**
   * 当前用户上下文（过渡桥）：优先契约口径（UserContextHolder），为空时回退 BaseUnit（RoleAO） 构造合法 UserContext，与
   * platform/config/CurrentUserResolver 的过渡策略一致； 契约接线（integrator 范畴）完成后自动生效契约口径。两者皆无视为未登录。
   */
  private UserContext currentUser() {
    UserContext user = UserContextHolder.current();
    UserContext result;
    if (user != null) {
      result = user;
    } else {
      RoleAO role = BaseUnit.getCurrentRole();
      if (role != null
          && role.getId() != null
          && role.getRoleId() != null
          && role.getDeptId() != null) {
        result =
            new UserContext(
                role.getId(), role.getRoleId(), role.getDeptId(), DataScopeLevel.NONE, null);
      } else {
        throw new BaseException(ErrorCode.TOKEN_ERROR);
      }
    }
    return result;
  }
}
