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
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.quota.QuotaDecision;
import com.slz.crm.platform.quota.QuotaDimension;
import com.slz.crm.platform.quota.RequestQuotaService;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 知识库管理服务（admin API 编排）。 复用既有 DocumentIngestionService / 检索 / 授权服务。 */
@Service
@Slf4j
public class KnowledgeAdminService {

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final UploadedFileMapper uploadedFileMapper;
  private final KnowledgeBaseAuthorizationService authorizationService;
  private final DocumentIngestionService ingestionService;
  private final KnowledgeRetrievalServiceImpl retrievalService;
  private final SparseRecallService sparseRecallService;
  private final DynamicConfigService dynamicConfigService;
  private final RequestQuotaService requestQuotaService;

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeAdminService(
      KnowledgeBaseMapper knowledgeBaseMapper,
      UploadedFileMapper uploadedFileMapper,
      KnowledgeBaseAuthorizationService authorizationService,
      DocumentIngestionService ingestionService,
      KnowledgeRetrievalServiceImpl retrievalService,
      SparseRecallService sparseRecallService,
      DynamicConfigService dynamicConfigService,
      RequestQuotaService requestQuotaService) {
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.uploadedFileMapper = uploadedFileMapper;
    this.authorizationService = authorizationService;
    this.ingestionService = ingestionService;
    this.retrievalService = retrievalService;
    this.sparseRecallService = sparseRecallService;
    this.dynamicConfigService = dynamicConfigService;
    this.requestQuotaService = requestQuotaService;
  }

  /** 兼容仅覆盖当前用户桥接逻辑的单元测试；生产装配使用六参构造注入稀疏召回服务。 */
  public KnowledgeAdminService(
      KnowledgeBaseMapper knowledgeBaseMapper,
      UploadedFileMapper uploadedFileMapper,
      KnowledgeBaseAuthorizationService authorizationService,
      DocumentIngestionService ingestionService,
      KnowledgeRetrievalServiceImpl retrievalService) {
    this(
        knowledgeBaseMapper,
        uploadedFileMapper,
        authorizationService,
        ingestionService,
        retrievalService,
        null,
        null,
        null);
  }

  public List<KnowledgeBaseVO> listBases() {
    UserContext user = currentUser();
    List<Long> visibleIds = authorizationService.visibleKnowledgeBaseIds(user);
    List<KnowledgeBaseVO> result = List.of();
    if (!visibleIds.isEmpty()) {
      List<KnowledgeBaseEntity> bases =
          knowledgeBaseMapper.selectList(
              new QueryWrapper<KnowledgeBaseEntity>().in("id", visibleIds).eq("is_deleted", false));
      result =
          bases.stream().map(KnowledgeAdminResponseMapper::toBaseVO).collect(Collectors.toList());
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
      result =
          files.stream().map(KnowledgeAdminResponseMapper::toFileVO).collect(Collectors.toList());
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
      result = KnowledgeAdminResponseMapper.toFileVO(file);
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
    if (req == null || req.getQuery() == null || req.getQuery().isBlank()) {
      throw new IllegalArgumentException("检索 query 不能为空");
    }
    boolean useVector = Boolean.TRUE.equals(req.getUseVector());
    if (useVector && !adminVectorEnabled()) {
      throw new BaseException(ErrorCode.SERVICE_UNAVAILABLE, "管理端真向量检索未开启");
    }
    int topK = resolveTopK(req.getTopK(), useVector);
    if (useVector && req.getKbId() == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "真向量检索必须指定一个知识库");
    }

    UserContext user = currentUser();
    List<Long> authorizedKbIds = resolveAuthorizedKnowledgeBaseIds(user, req.getKbId());

    KnowledgeAdminRetrievalResponse response = new KnowledgeAdminRetrievalResponse();
    response.setQuery(req.getQuery());
    response.setTopK(topK);
    response.setUsedVector(useVector);
    response.setCandidates(List.of());
    if (!authorizedKbIds.isEmpty()) {
      if (useVector) {
        acquireAdminVectorQuota(user);
        response.setCandidates(
            retrieveVectorCandidates(req.getQuery(), topK, user, authorizedKbIds));
      } else if (sparseRecallService != null) {
        response.setCandidates(
            sparseRecallService.recall(req.getQuery(), authorizedKbIds, null, topK).stream()
                .map(KnowledgeAdminResponseMapper::toCandidate)
                .toList());
      }
    }
    return response;
  }

  private List<Long> resolveAuthorizedKnowledgeBaseIds(UserContext user, Long kbId) {
    List<Long> result;
    if (kbId == null) {
      result = authorizationService.visibleKnowledgeBaseIds(user);
    } else {
      result = authorizationService.authorizedKnowledgeBaseIds(user, List.of(String.valueOf(kbId)));
    }
    return result == null ? List.of() : result;
  }

  private int resolveTopK(Integer requestedTopK, boolean useVector) {
    int result = requestedTopK == null ? RetrievalDefaults.TOP_K : requestedTopK;
    if (result <= 0) {
      if (useVector) {
        throw new BaseException(ErrorCode.PARAM_OUT_OF_RANGE, "topK 必须大于 0");
      }
      throw new IllegalArgumentException("topK 必须大于 0");
    }
    if (useVector && result > 10) {
      throw new BaseException(ErrorCode.PARAM_OUT_OF_RANGE, "管理端真向量检索 topK 必须不大于 10");
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private boolean adminVectorEnabled() {
    boolean enabled = false;
    if (dynamicConfigService != null) {
      try {
        enabled =
            Boolean.TRUE.equals(
                dynamicConfigService.get(
                    "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE));
      } catch (RuntimeException ex) {
        // 动态配置读取故障必须保持管理端真向量关闭，避免故障时意外产生模型调用。
        log.warn("读取管理端真向量开关失败，按关闭处理", ex);
      }
    }
    return enabled;
  }

  private void acquireAdminVectorQuota(UserContext user) {
    if (requestQuotaService == null) {
      throw new BaseException(ErrorCode.SERVICE_UNAVAILABLE, "管理端真向量配额服务不可用");
    }
    QuotaDecision decision =
        requestQuotaService.tryAcquire(
            QuotaDimension.ADMIN_VECTOR_USER, String.valueOf(user.userId()));
    if (!decision.allowed()) {
      throw new BaseException(ErrorCode.RATE_LIMIT_EXCEEDED, "管理端真向量检索请求频率超限");
    }
  }

  private List<KnowledgeAdminRetrievalResponse.Candidate> retrieveVectorCandidates(
      String query, int topK, UserContext user, List<Long> authorizedKbIds) {
    if (retrievalService == null) {
      throw new IllegalStateException("向量检索服务未装配");
    }
    KnowledgeRetrievalPort.RetrievalQuery retrievalQuery =
        new KnowledgeRetrievalPort.RetrievalQuery(
            query,
            user.userId(),
            authorizedKbIds.stream().map(String::valueOf).toList(),
            topK,
            null,
            null);
    KnowledgeRetrievalPort.RetrievalResult retrievalResult =
        retrievalService.retrieve(retrievalQuery);
    return retrievalResult.sources().stream()
        .map(KnowledgeAdminResponseMapper::toCandidate)
        .toList();
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
