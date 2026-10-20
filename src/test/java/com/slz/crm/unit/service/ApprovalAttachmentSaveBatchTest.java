package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.IOUtils;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.impl.ApprovalAttachmentServiceImpl;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
 * E-15 专项：ApprovalAttachmentServiceImpl.saveBatch 的「先全部落盘 → 短事务批量入库 → 失败清盘」形状。
 *
 * <p>旧实现为循环内「写盘 + 单条 insert」，无事务无补偿，中途失败会留下半提交附件；本测试锁定修复后的四条：成功路径 {@code insertBatch} 恰一次且单条 {@code
 * insert} 零调用、落库失败时已落盘文件全清且异常原样上抛（零半提交）、写盘阶段失败 同样清掉先前文件、空/null 列表零副作用。写盘使用真实 IOUtils（filePath
 * 指向 @TempDir），不使用静态 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("E-15 附件 saveBatch 落盘/批量入库/清盘补偿")
class ApprovalAttachmentSaveBatchTest {

  private static final Long CURRENT_USER_ID = 7L;
  private static final Long AND_ID = 500L;

  @Mock private ApprovalAttachmentMapper approvalAttachmentMapper;

  @Mock private PlatformTransactionManager transactionManager;

  @Mock private TransactionStatus transactionStatus;

  @Captor private ArgumentCaptor<List<ApprovalAttachmentEntity>> entitiesCaptor;

  @TempDir private Path tempDir;

  private ApprovalAttachmentServiceImpl service;

  private Object previousFilePath;

  @BeforeEach
  void setUp() {
    service = new ApprovalAttachmentServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", approvalAttachmentMapper);
    ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
    previousFilePath = ReflectionTestUtils.getField(IOUtils.class, "filePath");
    ReflectionTestUtils.setField(IOUtils.class, "filePath", tempDir.toString());
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
    ReflectionTestUtils.setField(IOUtils.class, "filePath", previousFilePath);
  }

  @Test
  @DisplayName("1. 成功路径：两文件真实落盘 + insertBatch 恰一次（单条 insert 零调用）+ 事务提交")
  void saveBatchWritesAllFilesThenSingleBatchInsert() {
    when(approvalAttachmentMapper.insertBatch(anyList())).thenReturn(2);

    service.saveBatch(List.of(dtoOf("a.txt"), dtoOf("b.txt")));

    assertEquals(2, storedFiles().length, "两个文件都应真实落盘");
    verify(approvalAttachmentMapper, times(1)).insertBatch(entitiesCaptor.capture());
    List<ApprovalAttachmentEntity> entities = entitiesCaptor.getValue();
    assertEquals(2, entities.size());
    assertEquals(CURRENT_USER_ID, entities.get(0).getUploaderId(), "旧逐条路径的 uploaderId 绑定语义保留");
    assertEquals(AND_ID, entities.get(0).getAndId());
    assertEquals(ModelName.APPROVAL_ATTACHMENT, entities.get(0).getModelName());
    verify(approvalAttachmentMapper, never()).insert(any(ApprovalAttachmentEntity.class));
    verify(transactionManager, times(1)).commit(transactionStatus);
  }

  @Test
  @DisplayName("2. 批量入库失败：事务回滚 + 已落盘两文件全清（零半提交）+ 原异常上抛")
  void saveBatchDbFailureCleansAllFilesAndRethrows() {
    when(approvalAttachmentMapper.insertBatch(anyList()))
        .thenThrow(new IllegalStateException("insert failed"));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.saveBatch(List.of(dtoOf("a.txt"), dtoOf("b.txt"))));

    assertEquals("insert failed", thrown.getMessage(), "异常原样上抛，不换类型不改文案");
    assertEquals(0, storedFiles().length, "落库失败必须清掉本次已落盘的全部文件");
    verify(transactionManager, times(1)).rollback(transactionStatus);
    verify(transactionManager, never()).commit(any());
  }

  @Test
  @DisplayName("3. 写盘阶段中途失败：清掉先前已写文件、零入库")
  void saveBatchWriteFailureCleansAlreadyWrittenFiles() {
    ServiceException thrown =
        assertThrows(
            ServiceException.class,
            () -> service.saveBatch(List.of(dtoOf("ok.txt"), dtoOfFailingTransfer())));

    assertEquals(0, storedFiles().length, "第二个文件写盘失败必须清掉第一个");
    verify(approvalAttachmentMapper, never()).insertBatch(anyList());
    verifyNoInteractions(transactionManager);
    assertEquals(
        com.slz.crm.server.constant.MessageConstant.FILE_CREATE_ERROR,
        thrown.getMessage(),
        "写盘失败沿用 IOUtils 原有异常类型与文案");
  }

  @Test
  @DisplayName("4. null / 空列表：零写盘零入库")
  void saveBatchIgnoresNullAndEmptyList() {
    service.saveBatch((List<ApprovalAttachmentDTO>) null);
    List<ApprovalAttachmentDTO> emptyList = List.of();
    service.saveBatch(emptyList);

    assertEquals(0, storedFiles().length);
    verifyNoInteractions(approvalAttachmentMapper, transactionManager);
  }

  private File[] storedFiles() {
    File dir =
        tempDir.resolve(ModelName.APPROVAL_ATTACHMENT).resolve(String.valueOf(AND_ID)).toFile();
    File[] files = dir.listFiles();
    return files == null ? new File[0] : files;
  }

  private ApprovalAttachmentDTO dtoOf(String fileName) {
    ApprovalAttachmentDTO dto = new ApprovalAttachmentDTO();
    dto.setAndId(AND_ID);
    dto.setModelName(ModelName.APPROVAL_ATTACHMENT);
    dto.setFileName(fileName);
    dto.setFileData(
        new MockMultipartFile("fileData", fileName, "text/plain", "payload".getBytes()));
    return dto;
  }

  private ApprovalAttachmentDTO dtoOfFailingTransfer() {
    ApprovalAttachmentDTO dto = new ApprovalAttachmentDTO();
    dto.setAndId(AND_ID);
    dto.setModelName(ModelName.APPROVAL_ATTACHMENT);
    dto.setFileName("bad.txt");
    dto.setFileData(
        new MockMultipartFile("fileData", "bad.txt", "text/plain", "payload".getBytes()) {
          @Override
          public void transferTo(File dest) throws IOException {
            throw new IOException("disk full");
          }
        });
    return dto;
  }
}
