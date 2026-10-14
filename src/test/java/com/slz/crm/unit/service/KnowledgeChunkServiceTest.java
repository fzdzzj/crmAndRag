package com.slz.crm.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.vo.KnowledgeChunkVO;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.service.KnowledgeChunkService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * add-source-citation-and-dashboard 任务 1.4：知识库 chunk 读取端点服务正反向单测（全 mock 桩，零外呼）。
 *
 * <p>覆盖：授权用户返回含定位字段（chunkIndex/pageNo/rowIndex）的切片列表 / 无知识库授权抛 12002 / 业务源 documentId 返回参数错误 /
 * 文档不存在返回参数错误 / 分页参数修正。端点权限（803）与知识库授权链的授权面由本服务 单测 + 控制器 IT 双覆盖。
 */
@DisplayName("知识库 chunk 读取服务（任务组 1）")
class KnowledgeChunkServiceTest {

  private final UploadedFileMapper uploadedFileMapper = mock(UploadedFileMapper.class);
  private final DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
  private final KnowledgeBaseAuthorizationService authorizationService =
      mock(KnowledgeBaseAuthorizationService.class);

  private KnowledgeChunkService service() {
    return new KnowledgeChunkService(uploadedFileMapper, chunkMapper, authorizationService);
  }

  private UserContext user(Long id) {
    return new UserContext(id, 3L, 10L, com.slz.crm.common.enumeration.DataScopeLevel.NONE, null);
  }

  private UploadedFileEntity file(String documentId, String kbId) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(documentId);
    file.setKnowledgeBase(kbId);
    return file;
  }

  private DocumentVectorChunkEntity chunk(Long id, int index, Integer pageNo, Integer rowIndex) {
    DocumentVectorChunkEntity c = new DocumentVectorChunkEntity();
    c.setId(id);
    c.setDocumentId("doc-1");
    c.setChunkIndex(index);
    c.setChunkText("切片正文" + index);
    c.setFilename("a.pdf");
    c.setPageNo(pageNo);
    c.setRowIndex(rowIndex);
    return c;
  }

  @SuppressWarnings("unchecked")
  private void stubChunkPage(List<DocumentVectorChunkEntity> rows, long total) {
    Page<DocumentVectorChunkEntity> page = new Page<>(1, 10);
    page.setTotal(total);
    page.setRecords(rows);
    when(chunkMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
  }

  @Test
  @SuppressWarnings("unchecked")
  void authorizedUser_returnsChunksWithLocationFieldsAscending() {
    when(uploadedFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(file("doc-1", "5"));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of(5L));
    stubChunkPage(List.of(chunk(1L, 0, 1, null), chunk(2L, 1, 1, 2)), 2);

    Page<KnowledgeChunkVO> result = service().listChunks("doc-1", 1, 10, user(42L));

    assertThat(result.getTotal()).isEqualTo(2);
    assertThat(result.getRecords()).hasSize(2);
    KnowledgeChunkVO first = result.getRecords().get(0);
    assertThat(first.getChunkId()).isEqualTo(1L);
    assertThat(first.getChunkIndex()).isEqualTo(0);
    assertThat(first.getPageNo()).isEqualTo(1);
    assertThat(first.getRowIndex()).isNull();
    assertThat(first.getChunkText()).isEqualTo("切片正文0");
    KnowledgeChunkVO second = result.getRecords().get(1);
    assertThat(second.getChunkIndex()).isEqualTo(1);
    assertThat(second.getPageNo()).isEqualTo(1);
    assertThat(second.getRowIndex()).isEqualTo(2);
  }

  @Test
  void unauthorizedUser_throwsPermissionDenied12002() {
    when(uploadedFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(file("doc-1", "5"));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service().listChunks("doc-1", 1, 10, user(42L)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED.getCode());
  }

  @Test
  void businessDocumentId_throwsParamError() {
    assertThatThrownBy(() -> service().listChunks("CUSTOMER_COMPANY:3", 1, 10, user(42L)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  @Test
  void missingDocument_throwsParamError() {
    when(uploadedFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

    assertThatThrownBy(() -> service().listChunks("doc-missing", 1, 10, user(42L)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  @Test
  void emptyDocumentId_throwsParamRequired() {
    assertThatThrownBy(() -> service().listChunks("  ", 1, 10, user(42L)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_REQUIRED.getCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void oversizedPageSize_throwsParamFormatError() {
    when(uploadedFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(file("doc-1", "5"));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of(5L));

    assertThatThrownBy(() -> service().listChunks("doc-1", 1, 1000, user(42L)))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }
}
