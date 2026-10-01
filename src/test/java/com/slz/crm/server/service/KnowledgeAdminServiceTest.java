package com.slz.crm.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.quota.QuotaDecision;
import com.slz.crm.platform.quota.QuotaDimension;
import com.slz.crm.platform.quota.RequestQuotaService;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class KnowledgeAdminServiceTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private UploadedFileMapper uploadedFileMapper;
  @Mock private KnowledgeBaseAuthorizationService authorizationService;
  @Mock private DocumentIngestionService ingestionService;
  @Mock private KnowledgeRetrievalServiceImpl retrievalService;
  @Mock private SparseRecallService sparseRecallService;
  @Mock private DynamicConfigService dynamicConfigService;
  @Mock private RequestQuotaService requestQuotaService;
  @Mock private MultipartFile multipartFile;

  @InjectMocks private KnowledgeAdminService knowledgeAdminService;

  private UserContext testUser;

  @BeforeEach
  void setUp() {
    testUser = new UserContext(1L, 3L, 10L, DataScopeLevel.NONE, "test");
  }

  @Test
  void listBases_shouldReturnVisible() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(any())).thenReturn(List.of(1L));
      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(1L);
      kb.setName("test-kb");
      when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));

      List<KnowledgeBaseVO> result = knowledgeAdminService.listBases();

      assertNotNull(result);
      assertEquals(1, result.size());
    }
  }

  @Test
  void upload_shouldCallIngestion() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(1L);
      when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
      when(authorizationService.canWrite(any(), any())).thenReturn(true);
      DocumentIngestionResult mockResult = new DocumentIngestionResult(1L, "doc1", 0, 0);
      when(ingestionService.ingest(any())).thenReturn(mockResult);

      DocumentIngestionResult result = knowledgeAdminService.upload(multipartFile, 1L);

      assertNotNull(result);
      verify(ingestionService).ingest(any());
    }
  }

  @Test
  void listFiles_defaultParams_shouldCallSelectListAndReturnVisible() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of(1L));
      when(uploadedFileMapper.selectList(any()))
          .thenReturn(List.of(uploadedFile(1L, "doc-1", "1")));

      List<KnowledgeFileVO> result = knowledgeAdminService.listFiles(null);

      assertEquals(1, result.size());
      assertEquals("doc-1", result.get(0).getDocumentId());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<QueryWrapper<UploadedFileEntity>> captor =
          ArgumentCaptor.forClass(QueryWrapper.class);
      verify(uploadedFileMapper).selectList(captor.capture());
      String sql = captor.getValue().getSqlSegment();
      assertTrue(sql.contains("create_time"));
      assertTrue(sql.contains("DESC"));
      verify(uploadedFileMapper, never()).selectPage(any(), any());
    }
  }

  @Test
  void listFiles_withPagination_shouldCallSelectPageAndReturnPagedList() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of(1L));
      Page<UploadedFileEntity> pageResult = new Page<>(1, 10, 1);
      pageResult.setRecords(List.of(uploadedFile(1L, "doc-1", "1")));
      doReturn(pageResult)
          .when(uploadedFileMapper)
          .selectPage(any(Page.class), any(QueryWrapper.class));

      List<KnowledgeFileVO> result = knowledgeAdminService.listFiles(null, 1, 10, "desc");

      assertEquals(1, result.size());
      assertEquals("doc-1", result.get(0).getDocumentId());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<Page<UploadedFileEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
      verify(uploadedFileMapper).selectPage(pageCaptor.capture(), any(QueryWrapper.class));
      assertEquals(1L, pageCaptor.getValue().getCurrent());
      assertEquals(10L, pageCaptor.getValue().getSize());
      verify(uploadedFileMapper, never()).selectList(any());
    }
  }

  @Test
  void listFiles_pageSizeExceedsMax_shouldThrowException() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of(1L));

      var ex =
          assertThrows(
              com.slz.crm.common.exiception.BaseException.class,
              () -> knowledgeAdminService.listFiles(null, 1, 101, "desc"));

      assertEquals(
          com.slz.crm.common.enumeration.ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
      verifyNoInteractions(uploadedFileMapper);
    }
  }

  @Test
  void listFiles_withAscSortOrder_shouldOrderAsc() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of(1L));
      when(uploadedFileMapper.selectList(any()))
          .thenReturn(List.of(uploadedFile(1L, "doc-1", "1")));

      List<KnowledgeFileVO> result = knowledgeAdminService.listFiles(null, null, null, "asc");

      assertEquals(1, result.size());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<QueryWrapper<UploadedFileEntity>> captor =
          ArgumentCaptor.forClass(QueryWrapper.class);
      verify(uploadedFileMapper).selectList(captor.capture());
      String sql = captor.getValue().getSqlSegment();
      assertTrue(sql.contains("create_time"));
      assertTrue(sql.contains("ASC"));
      assertFalse(sql.contains("DESC"));
    }
  }

  @Test
  void listFiles_unauthorizedKb_shouldReturnEmptyList() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.authorizedKnowledgeBaseIds(testUser, List.of("99")))
          .thenReturn(List.of());

      List<KnowledgeFileVO> result = knowledgeAdminService.listFiles(99L);

      assertTrue(result.isEmpty());
      verifyNoInteractions(uploadedFileMapper);
    }
  }

  @Test
  void retrievalTest_defaultSparse_returnsActualAuthorizedCandidates_withoutEmbedding() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of(10L, 20L));
      VectorSearchHit hit =
          new VectorSearchHit(
              "chunk-1",
              "doc-1",
              0.75D,
              "sparse result",
              java.util.Map.of("knowledgeBaseId", "10", "filename", "guide.md"));
      when(sparseRecallService.recall("test query", List.of(10L, 20L), null, 5))
          .thenReturn(List.of(new RetrievalCandidate(hit, 0.75D)));

      KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
      req.setQuery("test query");
      req.setUseVector(null);

      KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

      assertNotNull(resp);
      assertFalse(resp.getUsedVector());
      assertEquals(1, resp.getCandidates().size());
      assertEquals("chunk-1", resp.getCandidates().get(0).getChunkId());
      assertEquals(10L, resp.getCandidates().get(0).getKnowledgeBaseId());
      verify(sparseRecallService).recall("test query", List.of(10L, 20L), null, 5);
      verifyNoInteractions(retrievalService);
    }
  }

  @Test
  void retrievalTest_noVisibleKnowledgeBases_skipsSparseAndVectorServices() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.visibleKnowledgeBaseIds(testUser)).thenReturn(List.of());

      KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
      req.setQuery("no visible kb");

      KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

      assertTrue(resp.getCandidates().isEmpty());
      verify(authorizationService).visibleKnowledgeBaseIds(testUser);
      verifyNoInteractions(sparseRecallService, retrievalService);
    }
  }

  @Test
  void retrievalTest_vectorDisabledByDefault_rejectsBeforeAuthorizationOrQuota() {
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      KnowledgeAdminRetrievalRequest req = vectorRequest(42L, 3);

      var ex =
          assertThrows(
              com.slz.crm.common.exiception.BaseException.class,
              () -> knowledgeAdminService.retrievalTest(req));

      assertEquals(
          com.slz.crm.common.enumeration.ErrorCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
      verifyNoInteractions(authorizationService, requestQuotaService, retrievalService);
    }
  }

  @Test
  void retrievalTest_configReadFailure_failsClosedBeforeAuthorizationQuotaOrRetrieval() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenThrow(new IllegalStateException("dynamic config unavailable"));
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      KnowledgeAdminRetrievalRequest req = vectorRequest(42L, 3);

      var ex =
          assertThrows(
              com.slz.crm.common.exiception.BaseException.class,
              () -> knowledgeAdminService.retrievalTest(req));

      assertEquals(
          com.slz.crm.common.enumeration.ErrorCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
      verifyNoInteractions(authorizationService, requestQuotaService, retrievalService);
    }
  }

  @Test
  void retrievalTest_vectorInputBoundaries_rejectBeforeAuthorizationOrQuota() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenReturn(true);
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      for (int topK : new int[] {0, 11}) {
        KnowledgeAdminRetrievalRequest req = vectorRequest(42L, topK);

        var ex =
            assertThrows(
                com.slz.crm.common.exiception.BaseException.class,
                () -> knowledgeAdminService.retrievalTest(req));

        assertEquals(
            com.slz.crm.common.enumeration.ErrorCode.PARAM_OUT_OF_RANGE.getCode(), ex.getCode());
      }
      verifyNoInteractions(authorizationService, requestQuotaService, retrievalService);
    }
  }

  @Test
  void retrievalTest_vectorRequiresSingleKnowledgeBaseBeforeAuthorization() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenReturn(true);
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      KnowledgeAdminRetrievalRequest req = vectorRequest(null, 3);

      var ex =
          assertThrows(
              com.slz.crm.common.exiception.BaseException.class,
              () -> knowledgeAdminService.retrievalTest(req));

      assertEquals(com.slz.crm.common.enumeration.ErrorCode.PARAM_REQUIRED.getCode(), ex.getCode());
      verifyNoInteractions(authorizationService, requestQuotaService, retrievalService);
    }
  }

  @Test
  void retrievalTest_noAuthorizedRequestedKnowledgeBase_skipsSparseAndVectorServices() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenReturn(true);
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.authorizedKnowledgeBaseIds(testUser, List.of("99")))
          .thenReturn(List.of());

      KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
      req.setKbId(99L);
      req.setQuery("no authorized kb");
      req.setUseVector(true);

      KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

      assertTrue(resp.getCandidates().isEmpty());
      verify(authorizationService).authorizedKnowledgeBaseIds(testUser, List.of("99"));
      verifyNoInteractions(sparseRecallService, retrievalService);
    }
  }

  @Test
  void retrievalTest_vectorQuotaExceeded_rejectsBeforeRetrieval() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenReturn(true);
    when(requestQuotaService.tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "1"))
        .thenReturn(new QuotaDecision(false, 3, 3, 42, "RATE_LIMITED"));
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.authorizedKnowledgeBaseIds(testUser, List.of("42")))
          .thenReturn(List.of(42L));

      var ex =
          assertThrows(
              com.slz.crm.common.exiception.BaseException.class,
              () -> knowledgeAdminService.retrievalTest(vectorRequest(42L, 3)));

      assertEquals(
          com.slz.crm.common.enumeration.ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), ex.getCode());
      verify(requestQuotaService).tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "1");
      verifyNoInteractions(retrievalService);
    }
  }

  @Test
  void retrievalTest_useVector_true_delegatesExplicitlyAndScopesKb() {
    when(dynamicConfigService.get(
            "rag.retrieval.admin-vector.enabled", Boolean.class, Boolean.FALSE))
        .thenReturn(true);
    when(requestQuotaService.tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "1"))
        .thenReturn(new QuotaDecision(true, 1, 3, 0, "OK"));
    try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
      holder.when(UserContextHolder::current).thenReturn(testUser);
      when(authorizationService.authorizedKnowledgeBaseIds(testUser, List.of("42")))
          .thenReturn(List.of(42L));
      SourceReference source =
          new SourceReference(
              "md",
              "hybrid",
              "guide.md",
              "doc-1",
              "chunk-1",
              0,
              null,
              null,
              "vector result",
              0.91D);
      when(retrievalService.retrieve(any()))
          .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("context", List.of(source), 1));

      KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
      req.setKbId(42L);
      req.setQuery("vector test");
      req.setTopK(3);
      req.setUseVector(true);

      KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

      assertTrue(resp.getUsedVector());
      assertEquals(1, resp.getCandidates().size());
      assertEquals("vector result", resp.getCandidates().get(0).getText());
      ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> queryCaptor =
          ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
      verify(retrievalService).retrieve(queryCaptor.capture());
      KnowledgeRetrievalPort.RetrievalQuery captured = queryCaptor.getValue();
      assertEquals(testUser.userId(), captured.userId());
      assertEquals(List.of("42"), captured.kbScope());
      assertEquals("vector test", captured.query());
      assertEquals(3, captured.topK());
      verifyNoInteractions(sparseRecallService);
      verify(requestQuotaService).tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "1");
    }
  }

  private UploadedFileEntity uploadedFile(Long id, String documentId, String kbId) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(id);
    file.setDocumentId(documentId);
    file.setKnowledgeBase(kbId);
    file.setOriginalFilename(documentId + ".pdf");
    return file;
  }

  private KnowledgeAdminRetrievalRequest vectorRequest(Long kbId, int topK) {
    KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
    req.setKbId(kbId);
    req.setQuery("vector test");
    req.setTopK(topK);
    req.setUseVector(true);
    return req;
  }
}
