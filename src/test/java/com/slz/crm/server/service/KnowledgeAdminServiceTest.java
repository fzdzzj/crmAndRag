package com.slz.crm.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
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
  void retrievalTest_noAuthorizedRequestedKnowledgeBase_skipsSparseAndVectorServices() {
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
  void retrievalTest_useVector_true_delegatesExplicitlyAndScopesKb() {
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
    }
  }
}
