package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.ProjectFileServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * A-5+A-16 专项：ProjectFile 三上传方法合一的模板形状与事务边界迁移。
 *
 * <p>锁定四件事：①宿主缺失/权限拒绝闸的错误码与文案逐字不变且零落盘零落库；②先全部写盘、后短事务批量落库的顺序 （insertBatch 被调用时磁盘文件已全部存在）；③落库失败 →
 * 事务回滚 + 全量清盘补偿 + FILE_READ_FAILED 上抛；④落库形态为 单次 {@code insertBatch}（逐条 {@code insert}
 * 零调用），实体装饰按宿主注入。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A-5+A-16 ProjectFile 上传事务边界与批量落库")
class ProjectFileUploadTransactionTest {

  private static final Long CURRENT_USER_ID = 7L;
  private static final Long ACTIVITY_ID = 100L;
  private static final Long ORDER_ID = 200L;
  private static final Long CONTRACT_ID = 300L;
  private static final Long OPPORTUNITY_ID = 400L;

  @Mock private ProjectFileMapper projectFileMapper;

  @Mock private AttachmentAccessService attachmentAccessService;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private ContractOrderItemMapper contractOrderItemMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private DataConvertService dataConvertService;

  @Mock private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Mock private UserMapper userMapper;

  @Mock private HttpServletRequest request;

  @Mock private PlatformTransactionManager transactionManager;

  @Mock private TransactionStatus transactionStatus;

  @Captor private ArgumentCaptor<List<ProjectFileEntity>> entitiesCaptor;

  @TempDir private Path tempDir;

  private ProjectFileServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new ProjectFileServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", projectFileMapper);
    ReflectionTestUtils.setField(service, "attachmentAccessService", attachmentAccessService);
    ReflectionTestUtils.setField(service, "businessActivityMapper", businessActivityMapper);
    ReflectionTestUtils.setField(service, "contractOrderItemMapper", contractOrderItemMapper);
    ReflectionTestUtils.setField(service, "salesOpportunityMapper", salesOpportunityMapper);
    ReflectionTestUtils.setField(service, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(service, "downloadTokenUtil", downloadTokenUtil);
    ReflectionTestUtils.setField(service, "userMapper", userMapper);
    ReflectionTestUtils.setField(service, "request", request);
    ReflectionTestUtils.setField(service, "basePath", tempDir.toString());
    ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("1. 业务活动不存在：DATA_NULL 原文，零落盘零落库")
  void uploadByActivityMissingHostKeepsDataNull() {
    when(businessActivityMapper.selectById(ACTIVITY_ID)).thenReturn(null);

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () -> service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf("a.txt"))));

    assertEquals(ErrorCode.DATA_NULL.getCode(), thrown.getCode());
    assertEquals("业务活动不存在", thrown.getMessage());
    verifyNoInteractions(projectFileMapper);
    assertFalse(projectDir().exists(), "宿主缺失不得创建落盘目录");
  }

  @Test
  @DisplayName("2. 业务活动无写权限：PERMISSION_DENIED 原文，零落盘零落库")
  void uploadByActivityDeniedKeepsPermissionMessage() {
    when(businessActivityMapper.selectById(ACTIVITY_ID)).thenReturn(activityOf(ACTIVITY_ID, 9L));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(false);

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () -> service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf("a.txt"))));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals("无权向该业务活动上传附件", thrown.getMessage());
    verifyNoInteractions(projectFileMapper);
    assertFalse(projectDir().exists(), "权限拒绝不得创建落盘目录");
  }

  @Test
  @DisplayName("3. 业务活动成功：先盘后库（insertBatch 时文件已落盘）、单次批量、装饰 activityId+opportunityId")
  void uploadByActivityWritesDiskBeforeSingleBatchInsert() {
    when(businessActivityMapper.selectById(ACTIVITY_ID))
        .thenReturn(activityOf(ACTIVITY_ID, CURRENT_USER_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(true);
    AtomicBoolean filesPresentWhenInserting = new AtomicBoolean(false);
    when(projectFileMapper.insertBatch(anyList()))
        .thenAnswer(
            invocation -> {
              filesPresentWhenInserting.set(storedFileCount(ACTIVITY_ID) == 2);
              return 2;
            });

    service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf("a.txt"), dtoOf("b.txt")));

    assertTrue(filesPresentWhenInserting.get(), "落库时必须已完成全部写盘（先盘后库）");
    assertEquals(2, storedFileCount(ACTIVITY_ID), "两个文件都应真实落盘");
    verify(projectFileMapper, times(1)).insertBatch(entitiesCaptor.capture());
    List<ProjectFileEntity> entities = entitiesCaptor.getValue();
    assertEquals(2, entities.size());
    assertEquals(CURRENT_USER_ID, entities.get(0).getUploaderId());
    assertEquals(ACTIVITY_ID, entities.get(0).getActivityId());
    assertEquals(
        OPPORTUNITY_ID, entities.get(0).getOpportunityId(), "activityId+opportunityId 由宿主注入");
    assertEquals("VISIT_RECORD", entities.get(0).getCategory());
    assertNotNull(entities.get(0).getUploadTime());
    verify(projectFileMapper, never()).insert(any(ProjectFileEntity.class));
    verify(transactionManager, times(1)).commit(transactionStatus);
  }

  @Test
  @DisplayName("4. 业务活动落库失败：事务回滚 + 全量清盘 + FILE_READ_FAILED 上抛")
  void uploadByActivityDbFailureRollsBackAndCleansAllFiles() {
    when(businessActivityMapper.selectById(ACTIVITY_ID))
        .thenReturn(activityOf(ACTIVITY_ID, CURRENT_USER_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(true);
    when(projectFileMapper.insertBatch(anyList()))
        .thenThrow(new IllegalStateException("batch insert failed"));

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () -> service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf("a.txt"), dtoOf("b.txt"))));

    assertEquals(ErrorCode.FILE_READ_FAILED.getCode(), thrown.getCode());
    assertEquals("文件上传失败: batch insert failed", thrown.getMessage());
    assertEquals(0, storedFileCount(ACTIVITY_ID), "落库失败必须清空本次已写盘的 2 个文件");
    verify(transactionManager, times(1)).rollback(transactionStatus);
    verify(transactionManager, never()).commit(any());
  }

  @Test
  @DisplayName("5. 写盘阶段中途失败：清掉已写文件、零落库")
  void uploadByActivityDiskFailureCleansAlreadyWrittenFiles() {
    when(businessActivityMapper.selectById(ACTIVITY_ID))
        .thenReturn(activityOf(ACTIVITY_ID, CURRENT_USER_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID, CURRENT_USER_ID))
        .thenReturn(true);

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () -> service.uploadByActivity(ACTIVITY_ID, List.of(dtoOf("a.txt"), dtoOfEmptyData())));

    assertEquals(ErrorCode.FILE_READ_FAILED.getCode(), thrown.getCode());
    assertEquals(0, storedFileCount(ACTIVITY_ID), "第二个文件写盘失败必须清掉第一个");
    verify(projectFileMapper, never()).insertBatch(anyList());
    verifyNoInteractions(transactionManager);
  }

  @Test
  @DisplayName("6. 订单项不存在：DATA_NULL 原文")
  void uploadByOrderMissingHostKeepsDataNull() {
    when(contractOrderItemMapper.selectById(ORDER_ID)).thenReturn(null);

    BaseException thrown =
        assertThrows(
            BaseException.class, () -> service.uploadByOrder(ORDER_ID, List.of(dtoOf("a.txt"))));

    assertEquals(ErrorCode.DATA_NULL.getCode(), thrown.getCode());
    assertEquals("订单项不存在", thrown.getMessage());
    verifyNoInteractions(projectFileMapper);
  }

  @Test
  @DisplayName("7. 订单项成功：单次批量落库且装饰 contractId+orderId")
  void uploadByOrderDecoratesContractAndOrder() {
    when(contractOrderItemMapper.selectById(ORDER_ID))
        .thenReturn(orderItemOf(ORDER_ID, CONTRACT_ID));
    when(attachmentAccessService.canWriteAttachments(
            ModelName.CONTRACT_ORDER_ITEM, ORDER_ID, CURRENT_USER_ID))
        .thenReturn(true);
    when(projectFileMapper.insertBatch(anyList())).thenReturn(1);

    service.uploadByOrder(ORDER_ID, List.of(dtoOf("order.txt")));

    verify(projectFileMapper, times(1)).insertBatch(entitiesCaptor.capture());
    assertEquals(CONTRACT_ID, entitiesCaptor.getValue().get(0).getContractId());
    assertEquals(ORDER_ID, entitiesCaptor.getValue().get(0).getOrderId());
    verify(projectFileMapper, never()).insert(any(ProjectFileEntity.class));
  }

  @Test
  @DisplayName("8. 空列表：PARAM_EMPTY 原文，且不触碰任何宿主/Mapper")
  void uploadStandaloneEmptyListKeepsParamEmpty() {
    BaseException thrown =
        assertThrows(BaseException.class, () -> service.uploadStandalone(List.of()));

    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), thrown.getCode());
    assertEquals("上传文件列表不能为空", thrown.getMessage());
    verifyNoInteractions(projectFileMapper, salesOpportunityMapper, transactionManager);
  }

  @Test
  @DisplayName("9. 独立上传销售机会不存在：沿用 FILE_READ_FAILED 包裹口径，零落盘")
  void uploadStandaloneMissingOpportunityKeepsWrapping() {
    when(salesOpportunityMapper.selectById(OPPORTUNITY_ID)).thenReturn(null);

    BaseException thrown =
        assertThrows(
            BaseException.class,
            () -> service.uploadStandalone(List.of(dtoWithOpportunity("s.txt"))));

    assertEquals(ErrorCode.FILE_READ_FAILED.getCode(), thrown.getCode());
    assertEquals("文件上传失败: 销售机会不存在，opportunityId=400", thrown.getMessage());
    assertEquals(0, storedFileCount(null));
    verify(projectFileMapper, never()).insertBatch(anyList());
  }

  @Test
  @DisplayName("10. 独立上传成功：DTO 直填 opportunityId+contractId，落 standalone 目录")
  void uploadStandaloneDecoratesFromDto() {
    when(salesOpportunityMapper.selectById(OPPORTUNITY_ID))
        .thenReturn(new SalesOpportunityEntity());
    when(projectFileMapper.insertBatch(anyList())).thenReturn(1);

    service.uploadStandalone(List.of(dtoWithOpportunity("s.txt")));

    verify(projectFileMapper, times(1)).insertBatch(entitiesCaptor.capture());
    assertEquals(OPPORTUNITY_ID, entitiesCaptor.getValue().get(0).getOpportunityId());
    assertEquals(CONTRACT_ID, entitiesCaptor.getValue().get(0).getContractId());
    assertTrue(storedFileCount(null) == 1, "独立上传落 standalone 目录");
  }

  private File projectDir() {
    return tempDir.resolve(ModelName.PROJECT_FILE).toFile();
  }

  private int storedFileCount(Long relatedId) {
    File dir =
        relatedId == null
            ? tempDir.resolve(ModelName.PROJECT_FILE).resolve("standalone").toFile()
            : tempDir.resolve(ModelName.PROJECT_FILE).resolve(String.valueOf(relatedId)).toFile();
    String[] names = dir.list();
    return names == null ? 0 : names.length;
  }

  private BusinessActivityEntity activityOf(Long id, Long creatorId) {
    BusinessActivityEntity activity = new BusinessActivityEntity();
    activity.setId(id);
    activity.setCreatorId(creatorId);
    activity.setOpportunityId(OPPORTUNITY_ID);
    return activity;
  }

  private ContractOrderItemEntity orderItemOf(Long id, Long contractId) {
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(id);
    item.setContractId(contractId);
    return item;
  }

  private ProjectFileDTO dtoOf(String fileName) {
    ProjectFileDTO dto = new ProjectFileDTO();
    dto.setCategory("VISIT_RECORD");
    dto.setFileData(
        new MockMultipartFile("fileData", fileName, "text/plain", "payload".getBytes()));
    return dto;
  }

  private ProjectFileDTO dtoOfEmptyData() {
    ProjectFileDTO dto = new ProjectFileDTO();
    dto.setCategory("VISIT_RECORD");
    dto.setFileData(new MockMultipartFile("fileData", "empty.txt", "text/plain", new byte[0]));
    return dto;
  }

  private ProjectFileDTO dtoWithOpportunity(String fileName) {
    ProjectFileDTO dto = dtoOf(fileName);
    dto.setOpportunityId(OPPORTUNITY_ID);
    dto.setContractId(CONTRACT_ID);
    return dto;
  }
}
