package com.slz.crm.server.service;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.quota.QuotaDecision;
import com.slz.crm.platform.quota.QuotaDimension;
import com.slz.crm.platform.quota.RequestQuotaService;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端真向量检索协作类（拆自 {@link KnowledgeAdminService} 以守 PMD NcssCount）：动态开关读取、配额获取与
 * 真向量候选检索。无共享可变状态，可安全并发调用。
 */
@Slf4j
final class KnowledgeAdminVectorSupport {

  private final DynamicConfigService dynamicConfigService;
  private final RequestQuotaService requestQuotaService;
  private final KnowledgeRetrievalServiceImpl retrievalService;

  KnowledgeAdminVectorSupport(
      DynamicConfigService dynamicConfigService,
      RequestQuotaService requestQuotaService,
      KnowledgeRetrievalServiceImpl retrievalService) {
    this.dynamicConfigService = dynamicConfigService;
    this.requestQuotaService = requestQuotaService;
    this.retrievalService = retrievalService;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  boolean adminVectorEnabled() {
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

  void acquireQuota(UserContext user) {
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

  List<KnowledgeAdminRetrievalResponse.Candidate> retrieveCandidates(
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
}
