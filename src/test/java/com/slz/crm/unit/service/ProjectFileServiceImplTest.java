package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.ProjectFileServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * optimize-project-file-list-auth-reuse 任务 2.1：项目文件列表行级鉴权同请求复用专属单测。
 *
 * <p>纯 Mockito（不起 Spring 上下文），锁定变更 spec-delta 契约 1/2：5 个列表读取路径对每个候选行 {@code canReadProjectFile}
 * 在同一请求内恰好判定 1 次（旧形状 N+k 次时「恰 N 次」断言必红）、 可读行签发下载令牌、不可读行被过滤、{@code total} 保留库内条件总数、上传人姓名转换仍生效、
 * 空列表零判定调用。判定语义矩阵本身由既有 {@code AttachmentAccessServiceTest} 锁定，此处不重复。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("项目文件列表行级判定同请求复用")
class ProjectFileServiceImplTest {

  private static final Long CURRENT_USER_ID = 7L;

  @Mock private ProjectFileMapper projectFileMapper;

  @Mock private AttachmentAccessService attachmentAccessService;

  @Mock private DataConvertService dataConvertService;

  @Mock private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Mock private HttpServletRequest request;

  private ProjectFileServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new ProjectFileServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", projectFileMapper);
    ReflectionTestUtils.setField(service, "attachmentAccessService", attachmentAccessService);
    ReflectionTestUtils.setField(service, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(service, "downloadTokenUtil", downloadTokenUtil);
    ReflectionTestUtils.setField(service, "request", request);
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("queryPage 混合可读页：records 仅含可读行、可读行签发令牌、判定恰 N 次")
  void queryPageMixedPageJudgesEachRowExactlyOnce() {
    Page<ProjectFileEntity> entityPage = pageOf(3L, row(1L, 11L), row(2L, 11L), row(3L, 11L));
    doReturn(entityPage).when(projectFileMapper).selectPage(any(Page.class), any());
    stubReadable(1L, true);
    stubReadable(2L, true);
    stubReadable(3L, false);
    stubUploaderName(11L);
    // 旧形状（N+k）下混合页的保留行二次判定返回 false、不签发令牌，该 stub 仅新形状消费
    lenient()
        .when(
            downloadTokenUtil.generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file")))
        .thenReturn("token-1");

    Page<ProjectFileVO> result = service.queryPage(1, 10, new ProjectFileQueryDTO());

    assertEquals(List.of(1L, 2L), idsOf(result.getRecords()), "records 应仅含可读行");
    assertTrue(
        result.getRecords().stream().allMatch(vo -> vo.getDownloadUrl() != null),
        "每个可读行都应签发 downloadUrl");
    verify(attachmentAccessService, times(3))
        .canReadProjectFile(any(ProjectFileEntity.class), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
  }

  @Test
  @DisplayName("queryPage 全无权页：records 空而 total 保留库内条件总数")
  void queryPageAllDeniedKeepsDatabaseTotal() {
    Page<ProjectFileEntity> entityPage = pageOf(5L, row(1L, 11L), row(2L, 11L));
    doReturn(entityPage).when(projectFileMapper).selectPage(any(Page.class), any());
    stubReadable(1L, false);
    stubReadable(2L, false);

    Page<ProjectFileVO> result = service.queryPage(1, 10, null);

    assertTrue(result.getRecords().isEmpty(), "全无权页 records 应为空");
    assertEquals(5L, result.getTotal(), "total 必须保留库内条件总数，而不是筛后可读条数");
    verify(attachmentAccessService, times(2))
        .canReadProjectFile(any(ProjectFileEntity.class), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
    verifyNoInteractions(downloadTokenUtil);
  }

  @Test
  @DisplayName("listByActivityId：每行判定恰 1 次并签发令牌")
  void listByActivityIdJudgesEachRowExactlyOnce() {
    assertSingleVerdictPerRow(service -> service.listByActivityId(100L));
  }

  @Test
  @DisplayName("listByOrderId：每行判定恰 1 次并签发令牌")
  void listByOrderIdJudgesEachRowExactlyOnce() {
    assertSingleVerdictPerRow(service -> service.listByOrderId(200L));
  }

  @Test
  @DisplayName("listByContractId：每行判定恰 1 次并签发令牌")
  void listByContractIdJudgesEachRowExactlyOnce() {
    assertSingleVerdictPerRow(service -> service.listByContractId(300L));
  }

  @Test
  @DisplayName("listByOpportunityId：每行判定恰 1 次并签发令牌")
  void listByOpportunityIdJudgesEachRowExactlyOnce() {
    assertSingleVerdictPerRow(service -> service.listByOpportunityId(400L));
  }

  @Test
  @DisplayName("上传人姓名转换仍生效：仅对非空 uploaderId 调用 getUserName")
  void uploaderNameConversionStillApplies() {
    doReturn(List.of(row(1L, 11L), row(2L, null))).when(projectFileMapper).selectList(any());
    stubReadable(1L, true);
    stubReadable(2L, true);
    when(dataConvertService.getUserName(11L)).thenReturn("张三");
    when(downloadTokenUtil.generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file")))
        .thenReturn("token-1");

    List<ProjectFileVO> result = service.listByActivityId(100L);

    assertEquals("张三", result.get(0).getUploaderName(), "上传人姓名应来自 dataConvertService.getUserName");
    assertNull(result.get(1).getUploaderName(), "uploaderId 为空的行不应触发姓名转换");
    verify(dataConvertService).getUserName(11L);
    verify(attachmentAccessService, times(2))
        .canReadProjectFile(any(ProjectFileEntity.class), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
  }

  @Test
  @DisplayName("空列表边界：零判定调用")
  void emptyResultSkipsAuthCompletely() {
    doReturn(pageOf(0L)).when(projectFileMapper).selectPage(any(Page.class), any());
    doReturn(List.of()).when(projectFileMapper).selectList(any());

    Page<ProjectFileVO> paged = service.queryPage(1, 10, null);
    List<ProjectFileVO> listed = service.listByOrderId(200L);

    assertTrue(paged.getRecords().isEmpty(), "空页 records 应为空");
    assertTrue(listed.isEmpty(), "空列表路径应返回空");
    verifyNoInteractions(attachmentAccessService, downloadTokenUtil, dataConvertService);
  }

  /**
   * listByXxx 公共断言链（optimize-project-file-list-auth-reuse 任务 2.1 第 3 项）： 2 行全可读 → 判定恰 2 次（恰 1 次/行）+
   * 2 个 VO + 2 个令牌；旧形状 4 次判定时「恰 2 次」必红。
   */
  private void assertSingleVerdictPerRow(
      Function<ProjectFileServiceImpl, List<ProjectFileVO>> path) {
    doReturn(List.of(row(1L, 11L), row(2L, 11L))).when(projectFileMapper).selectList(any());
    stubReadable(1L, true);
    stubReadable(2L, true);
    stubUploaderName(11L);
    when(downloadTokenUtil.generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file")))
        .thenReturn("token-1");

    List<ProjectFileVO> result = path.apply(service);

    assertEquals(List.of(1L, 2L), idsOf(result), "records 应仅含可读行");
    assertTrue(
        result.stream().allMatch(vo -> vo.getDownloadUrl() != null), "每个可读行都应签发 downloadUrl");
    verify(attachmentAccessService, times(2))
        .canReadProjectFile(any(ProjectFileEntity.class), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
    verify(downloadTokenUtil, times(2))
        .generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file"));
  }

  /** 按文件 ID 精确打桩可读性判定结果（同实体在新旧两种形状下命中同一 stub）。 */
  private void stubReadable(Long fileId, boolean readable) {
    when(attachmentAccessService.canReadProjectFile(
            argThat(file -> file != null && fileId.equals(file.getId())), eq(CURRENT_USER_ID)))
        .thenReturn(readable);
  }

  private void stubUploaderName(Long uploaderId) {
    when(dataConvertService.getUserName(uploaderId)).thenReturn("张三");
  }

  private Page<ProjectFileEntity> pageOf(long total, ProjectFileEntity... rows) {
    Page<ProjectFileEntity> page = new Page<>(1, 10, total);
    page.setRecords(List.of(rows));
    return page;
  }

  private ProjectFileEntity row(Long id, Long uploaderId) {
    ProjectFileEntity entity = new ProjectFileEntity();
    entity.setId(id);
    entity.setUploaderId(uploaderId);
    entity.setActivityId(100L);
    entity.setFileName("file-" + id + ".pdf");
    return entity;
  }

  private List<Long> idsOf(List<ProjectFileVO> records) {
    return records.stream().map(ProjectFileVO::getId).toList();
  }
}
