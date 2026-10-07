package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AttachmentAccessServiceImpl;
import com.slz.crm.server.service.impl.ProjectFileServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p>纯 Mockito（不起 Spring 上下文），锁定变更 spec-delta 契约 1/2 的调用形状：5 个列表读取路径对整页行集 {@code
 * filterReadableProjectFiles} 在同一请求内恰好调用 1 次（batch-project-file-list-auth-reads 任务 3.4 更新：形状由「每行
 * {@code canReadProjectFile} 恰 1 次」批量化为「每请求恰 1 次批量过滤」）、可读行签发 下载令牌、不可读行被过滤、{@code total}
 * 保留库内条件总数、上传人姓名转换仍生效、空列表零判定调用。 批量判定语义矩阵本身由 {@code AttachmentAccessServiceTest} 锁定，此处不重复。
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

  // batch-project-file-list-auth-reads：列表链路 user 读批量化形状反例所需的真实授权实现依赖
  @Mock private UserMapper userMapper;
  @Mock private AssistRequestMapper assistRequestMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private BusinessActivityUserMapper businessActivityUserMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private ContractMapper contractMapper;
  @Mock private ContractOrderItemMapper contractOrderItemMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private PermissionService permissionService;

  /** 按文件 ID 登记的可读性（批量过滤 stub 的回答依据，语义与单行逐行判定一致）。 */
  private final Set<Long> batchReadableIds = new HashSet<>();

  private boolean batchStubArmed;

  private AttachmentAccessServiceImpl realAccessService;

  private ProjectFileServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new ProjectFileServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", projectFileMapper);
    ReflectionTestUtils.setField(service, "attachmentAccessService", attachmentAccessService);
    ReflectionTestUtils.setField(service, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(service, "downloadTokenUtil", downloadTokenUtil);
    ReflectionTestUtils.setField(service, "request", request);
    realAccessService =
        new AttachmentAccessServiceImpl(
            assistRequestMapper,
            businessActivityMapper,
            businessActivityUserMapper,
            contactTaskMapper,
            salesOpportunityMapper,
            contractMapper,
            contractOrderItemMapper,
            salesStageApprovalMapper,
            userMapper,
            permissionService,
            new ObjectMapper());
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("queryPage 混合可读页：records 仅含可读行、可读行签发令牌、批量过滤每请求恰 1 次")
  void queryPageMixedPageBatchedFilterKeepsReadableSubset() {
    Page<ProjectFileEntity> entityPage = pageOf(3L, row(1L, 11L), row(2L, 11L), row(3L, 11L));
    doReturn(entityPage).when(projectFileMapper).selectPage(any(Page.class), any());
    stubReadable(1L, true);
    stubReadable(2L, true);
    stubReadable(3L, false);
    stubUploaderName(11L);
    when(downloadTokenUtil.generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file")))
        .thenReturn("token-1");

    Page<ProjectFileVO> result = service.queryPage(1, 10, new ProjectFileQueryDTO());

    assertEquals(List.of(1L, 2L), idsOf(result.getRecords()), "records 应仅含可读行");
    assertTrue(
        result.getRecords().stream().allMatch(vo -> vo.getDownloadUrl() != null),
        "每个可读行都应签发 downloadUrl");
    verify(attachmentAccessService, times(1))
        .filterReadableProjectFiles(anyList(), eq(CURRENT_USER_ID));
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
    verify(attachmentAccessService, times(1))
        .filterReadableProjectFiles(anyList(), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
    verifyNoInteractions(downloadTokenUtil);
  }

  @Test
  @DisplayName("listByActivityId：批量过滤每请求恰 1 次并签发令牌")
  void listByActivityIdBatchedFilterOncePerRequest() {
    assertBatchedFilterPerRequest(service -> service.listByActivityId(100L));
  }

  @Test
  @DisplayName("listByOrderId：批量过滤每请求恰 1 次并签发令牌")
  void listByOrderIdBatchedFilterOncePerRequest() {
    assertBatchedFilterPerRequest(service -> service.listByOrderId(200L));
  }

  @Test
  @DisplayName("listByContractId：批量过滤每请求恰 1 次并签发令牌")
  void listByContractIdBatchedFilterOncePerRequest() {
    assertBatchedFilterPerRequest(service -> service.listByContractId(300L));
  }

  @Test
  @DisplayName("listByOpportunityId：批量过滤每请求恰 1 次并签发令牌")
  void listByOpportunityIdBatchedFilterOncePerRequest() {
    assertBatchedFilterPerRequest(service -> service.listByOpportunityId(400L));
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
    verify(attachmentAccessService, times(1))
        .filterReadableProjectFiles(anyList(), eq(CURRENT_USER_ID));
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
   * batch-project-file-list-auth-reads 任务 3.1：列表链路鉴权 user 读批量化形状反例（红测试先行）。
   *
   * <p>真实 {@link AttachmentAccessServiceImpl} 接入列表链路：3 行活动维度全可读时， {@code sys_user} 状态闸读取在批量形状下必须恰 1
   * 次（旧形状逐行判定 = 3 次，本用例未改主代码实跑贴红）。 判定结果语义不受影响：3 行全部保留并签发令牌。
   */
  @Test
  @DisplayName("列表链路鉴权 user 读批量化：3 行活动维度页 sys_user 读恰 1 次")
  void queryPageAuthUserReadIsBatchedToSingleRead() {
    ProjectFileServiceImpl wired = new ProjectFileServiceImpl();
    ReflectionTestUtils.setField(wired, "baseMapper", projectFileMapper);
    ReflectionTestUtils.setField(wired, "attachmentAccessService", realAccessService);
    ReflectionTestUtils.setField(wired, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(wired, "downloadTokenUtil", downloadTokenUtil);
    ReflectionTestUtils.setField(wired, "request", request);

    Page<ProjectFileEntity> entityPage =
        pageOf(3L, activityRow(1L), activityRow(2L), activityRow(3L));
    doReturn(entityPage).when(projectFileMapper).selectPage(any(Page.class), any());
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(activeNonAdminUser());
    when(permissionService.hasPermission(
            CURRENT_USER_ID, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY))
        .thenReturn(true);
    // 旧形状消费 selectById、新形状消费 selectBatchIds + 参与人 IN：两侧打桩均 lenient，形状红绿翻转不因未消费桩报错
    lenient().when(businessActivityMapper.selectById(100L)).thenReturn(creatorActivity());
    lenient()
        .when(businessActivityMapper.selectBatchIds(any()))
        .thenReturn(List.of(creatorActivity()));
    lenient().when(businessActivityUserMapper.selectByActivityIds(any())).thenReturn(List.of());
    when(dataConvertService.getUserName(11L)).thenReturn("张三");
    when(downloadTokenUtil.generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file")))
        .thenReturn("token-1");

    Page<ProjectFileVO> result = wired.queryPage(1, 10, new ProjectFileQueryDTO());

    assertEquals(List.of(1L, 2L, 3L), idsOf(result.getRecords()), "3 行全可读语义不变");
    verify(userMapper, times(1)).selectById(CURRENT_USER_ID);
  }

  /**
   * listByXxx 公共断言链（batch-project-file-list-auth-reads 任务 3.4 更新调用形状）： 2 行全可读 → 批量过滤每请求恰 1 次 + 2 个
   * VO + 2 个令牌；旧形状（逐行 N 次判定）下本断言必红。
   */
  private void assertBatchedFilterPerRequest(
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
    verify(attachmentAccessService, times(1))
        .filterReadableProjectFiles(anyList(), eq(CURRENT_USER_ID));
    verifyNoMoreInteractions(attachmentAccessService);
    verify(downloadTokenUtil, times(2))
        .generateDownloadToken(any(), eq(CURRENT_USER_ID), eq("project_file"));
  }

  /**
   * 按文件 ID 登记可读性（batch-project-file-list-auth-reads 任务 3.4：判定入口批量化后， stub 以「可读 ID
   * 集」回答整页行集过滤，语义与逐行判定一致）。
   */
  private void stubReadable(Long fileId, boolean readable) {
    if (readable) {
      batchReadableIds.add(fileId);
    } else {
      batchReadableIds.remove(fileId);
    }
    if (!batchStubArmed) {
      when(attachmentAccessService.filterReadableProjectFiles(anyList(), eq(CURRENT_USER_ID)))
          .thenAnswer(
              invocation -> {
                List<ProjectFileEntity> files = invocation.getArgument(0);
                return files.stream()
                    .filter(file -> file != null && batchReadableIds.contains(file.getId()))
                    .toList();
              });
      batchStubArmed = true;
    }
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

  /** batch-project-file-list-auth-reads 任务 3.1：活动维度行（activityId=100，上传人 11）。 */
  private ProjectFileEntity activityRow(Long id) {
    return row(id, 11L);
  }

  /** batch-project-file-list-auth-reads 任务 3.1：非超管在职用户（roleId=2, status=1）。 */
  private UserEntity activeNonAdminUser() {
    UserEntity user = new UserEntity();
    user.setId(CURRENT_USER_ID);
    user.setRoleId(2L);
    user.setStatus(1);
    return user;
  }

  /** batch-project-file-list-auth-reads 任务 3.1：当前用户创建的活动实体。 */
  private BusinessActivityEntity creatorActivity() {
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(100L);
    activity.setCreatorId(CURRENT_USER_ID);
    return activity;
  }

  private List<Long> idsOf(List<ProjectFileVO> records) {
    return records.stream().map(ProjectFileVO::getId).toList();
  }
}
