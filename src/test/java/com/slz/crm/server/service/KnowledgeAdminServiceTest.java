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
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.pojo.vo.KnowledgeBaseVO;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  void retrievalTest_defaultSparse_noEmbedding() {
    KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
    req.setQuery("test query");
    req.setUseVector(false);

    KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

    assertNotNull(resp);
    assertFalse(resp.getUsedVector());
  }

  @Test
  void retrievalTest_useVector_true() {
    KnowledgeAdminRetrievalRequest req = new KnowledgeAdminRetrievalRequest();
    req.setQuery("vector test");
    req.setUseVector(true);

    KnowledgeAdminRetrievalResponse resp = knowledgeAdminService.retrievalTest(req);

    assertTrue(resp.getUsedVector());
  }
}
