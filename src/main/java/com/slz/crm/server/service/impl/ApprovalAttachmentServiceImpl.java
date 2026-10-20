package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.IOUtils;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ApprovalAttachmentServiceImpl
    extends ServiceImpl<ApprovalAttachmentMapper, ApprovalAttachmentEntity>
    implements ApprovalAttachmentService {

  @Autowired private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private AssistRequestMapper assistRequestMapper;

  @Autowired private SalesStageApprovalMapper salesStageApprovalMapper;

  @Autowired private HttpServletRequest request;

  @Autowired private UserMapper userMapper;

  @Autowired private AttachmentAccessService attachmentAccessService;

  /** 批量落库的短事务管理器（E-15：磁盘写入已移出事务，仅批量 INSERT 走此事务）。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** VO 组装与删除权判定协作类工厂：以本类既有依赖构造，保持单测零注入可测（任务 6.3 拆出）。 */
  private ApprovalAttachmentVoSupport voSupport() {
    return new ApprovalAttachmentVoSupport(
        baseMapper,
        userMapper,
        businessActivityMapper,
        contactTaskMapper,
        assistRequestMapper,
        salesStageApprovalMapper,
        downloadTokenUtil,
        attachmentAccessService);
  }

  /**
   * 批量保存附件（E-15 行为增强）。
   *
   * <p>旧实现「逐条写盘 + 逐条 insert，中途失败留半提交附件」；现改为：先全部 IOUtils.writeFile 落盘（任一失败即清盘已写
   * 文件并按原异常上抛），再在短事务内一次批量 INSERT（落库失败 → 事务回滚 + 清盘 + 原异常上抛）。对外签名与异常类型不变。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 写盘+落库多源，失败按原异常上抛并清盘补偿
  @Override
  public void saveBatch(List<ApprovalAttachmentDTO> list) {
    if (list == null || list.isEmpty()) {
      return;
    }

    Long currentUserId = BaseUnit.getCurrentId();
    List<ApprovalAttachmentEntity> entities = new ArrayList<>(list.size());
    List<File> writtenFiles = new ArrayList<>(list.size());

    try {
      for (ApprovalAttachmentDTO dto : list) {
        dto.setUploaderId(currentUserId);
        ApprovalAttachmentEntity entity = IOUtils.writeFile(dto);
        entities.add(entity);
        writtenFiles.add(new File(entity.getFilePath(), entity.getFileName()));
      }
    } catch (Exception e) {
      log.error("附件写盘失败，回滚已写入的 {} 个文件", writtenFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(writtenFiles);
      throw e;
    }

    try {
      transactionTemplate().executeWithoutResult(status -> baseMapper.insertBatch(entities));
    } catch (Exception e) {
      log.error("附件落库失败，回滚已写入的 {} 个文件", writtenFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(writtenFiles);
      throw e;
    }
  }

  /** 批量落库短事务：仅覆盖 INSERT，磁盘 IO 不进事务。 */
  private TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  @Override
  public void saveAttachments(Long approvalId, List<ApprovalAttachmentDTO> attachmentDTOList) {
    // 兼容旧方法，使用默认的模型名称
    saveAttachments(approvalId, ModelName.APPROVAL_ATTACHMENT, attachmentDTOList);
  }

  // ========== 通用附件管理方法实现 ==========

  @Override
  public void removeByIds(List<Long> ids, String modelName) {
    List<ApprovalAttachmentEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                .in(ApprovalAttachmentEntity::getId, ids)
                .eq(ApprovalAttachmentEntity::getModelName, modelName));

    // 如果没有找到附件记录，直接返回
    if (entities == null || entities.isEmpty()) {
      return;
    }

    // 先删除文件系统中的文件（如果失败会抛出异常，数据库不会被修改）
    IOUtils.deleteFile(entities);

    // 文件删除成功后，再删除数据库记录
    // fix-backlog-batch3 任务 4.1：只删「按 model_name 过滤后」的行——原先直接删原始 ids，ids 里混入他域附件
    // 时会把被过滤掉的跨模型行一并物理删掉（跨模型误删）。口径与 removeSourceAttachmentsByDistinctIds 一致。
    baseMapper.deleteBatchIds(entities.stream().map(ApprovalAttachmentEntity::getId).toList());
  }

  @Override
  @Privacy
  public List<ApprovalAttachmentVO> getByAndIds(List<Long> andIds, String modelName) {
    return getByAndIds(andIds, modelName, null);
  }

  @Override
  @Privacy
  public List<ApprovalAttachmentVO> getByAndIds(
      List<Long> andIds, String modelName, Long activeAssistId) {
    List<ApprovalAttachmentEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                .in(ApprovalAttachmentEntity::getAndId, andIds)
                .eq(ApprovalAttachmentEntity::getModelName, modelName));
    return voSupport().readFileWithEnrichment(entities, modelName, activeAssistId, getBaseUrl());
  }

  /**
   * 协助来源附件读取不经过普通业务的 Privacy 切面；AssistRequestService 已先校验 当前用户是本条协助参与人且来源记录严格匹配，下载 URL 仍绑定
   * activeAssistId。
   */
  @Override
  public List<ApprovalAttachmentVO> getByAndIdsForAssist(
      List<Long> andIds, String modelName, Long activeAssistId) {
    List<ApprovalAttachmentVO> vos = getByAndIds(andIds, modelName, activeAssistId);
    return vos;
  }

  /** 获取基础URL */
  private String getBaseUrl() {
    String contextPath = request.getContextPath();
    // 只返回协议、主机和端口后面的部分（不包括 http://localhost:8080）
    StringBuilder baseUrl = new StringBuilder();
    if (contextPath != null && !contextPath.isEmpty()) {
      baseUrl.append(contextPath);
    }
    return baseUrl.toString();
  }

  @Override
  public void saveAttachments(
      Long andId, String modelName, List<ApprovalAttachmentDTO> attachmentDTOList) {
    if (attachmentDTOList == null || attachmentDTOList.isEmpty()) {
      return;
    }

    Long currentUserId = BaseUnit.getCurrentId();
    for (ApprovalAttachmentDTO attachmentDTO : attachmentDTOList) {
      attachmentDTO.setAndId(andId);
      attachmentDTO.setModelName(modelName);
      attachmentDTO.setUploaderId(currentUserId);
    }

    saveBatch(attachmentDTOList);
  }

  @Override
  public void removeByAndIds(List<Long> andIds, String modelName) {
    if (andIds != null && !andIds.isEmpty()) {
      List<ApprovalAttachmentEntity> entities =
          baseMapper.selectList(
              new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                  .in(ApprovalAttachmentEntity::getAndId, andIds)
                  .eq(ApprovalAttachmentEntity::getModelName, modelName));
      if (entities != null && !entities.isEmpty()) {
        IOUtils.deleteFile(entities);
        baseMapper.delete(
            new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                .in(ApprovalAttachmentEntity::getAndId, andIds)
                .eq(ApprovalAttachmentEntity::getModelName, modelName));
      }
    }
  }

  @Override
  public void assertCanDelete(List<Long> attachmentIds, String modelName) {
    if (attachmentIds != null && !attachmentIds.isEmpty()) {
      List<ApprovalAttachmentEntity> entities =
          baseMapper.selectList(
              new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                  .in(ApprovalAttachmentEntity::getId, attachmentIds)
                  .eq(ApprovalAttachmentEntity::getModelName, modelName));
      if (entities != null && !entities.isEmpty()) {
        assertCanDeleteEntities(entities);
      }
    }
  }

  /** 对已加载实体逐条判定删除权：管理员放行，其余须为上传人/申请人/创建人 */
  private void assertCanDeleteEntities(List<ApprovalAttachmentEntity> entities) {
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (isAdmin) {
      return;
    }
    for (ApprovalAttachmentEntity entity : entities) {
      // 自己传的：可删
      if (Objects.equals(entity.getUploaderId(), currentId)) {
        continue;
      }
      // 申请人 / 业务创建人：可删
      if (voSupport().isApplicantOrCreator(entity, currentId)) {
        continue;
      }
      throw new com.slz.crm.common.exiception.BaseException(
          com.slz.crm.common.enumeration.ErrorCode.PERMISSION_DENIED, "仅上传人本人、申请人或业务创建人可删除该附件");
    }
  }

  @Override
  public AttachmentDeleteResultVO removeAuthorizedByIds(
      List<Long> attachmentIds, String modelName, Long expectedAndId) {
    AttachmentDeleteResultVO result = new AttachmentDeleteResultVO();
    List<Long> distinctIds =
        attachmentIds == null
            ? Collections.emptyList()
            : attachmentIds.stream().filter(Objects::nonNull).distinct().toList();
    if (!distinctIds.isEmpty()) {
      voSupport().deleteAuthorizedAttachments(result, distinctIds, modelName, expectedAndId);
    }
    return result;
  }

  @Override
  public AttachmentDeleteResultVO removeSourceAttachments(
      List<Long> attachmentIds, String modelName, Long sourceRecordId) {
    AttachmentDeleteResultVO result = new AttachmentDeleteResultVO();
    List<Long> distinctIds =
        attachmentIds == null
            ? Collections.emptyList()
            : attachmentIds.stream().filter(Objects::nonNull).distinct().toList();
    if (modelName != null && sourceRecordId != null && !distinctIds.isEmpty()) {
      result = removeSourceAttachmentsByDistinctIds(distinctIds, modelName, sourceRecordId);
    }
    return result;
  }

  /** 主体删除：非当前协助来源的进 denied，放行的删除文件与记录 */
  private AttachmentDeleteResultVO removeSourceAttachmentsByDistinctIds(
      List<Long> distinctIds, String modelName, Long sourceRecordId) {
    AttachmentDeleteResultVO result = new AttachmentDeleteResultVO();
    Map<Long, ApprovalAttachmentEntity> entityById =
        baseMapper.selectBatchIds(distinctIds).stream()
            .collect(Collectors.toMap(ApprovalAttachmentEntity::getId, Function.identity()));
    List<ApprovalAttachmentEntity> allowed = new java.util.ArrayList<>();
    for (Long attachmentId : distinctIds) {
      ApprovalAttachmentEntity entity = entityById.get(attachmentId);
      if (entity == null) {
        result.getNotFoundIds().add(attachmentId);
      } else if (!Objects.equals(modelName, entity.getModelName())
          || !Objects.equals(sourceRecordId, entity.getAndId())) {
        result.getDenied().put(attachmentId, "附件不属于当前协助来源");
      } else {
        allowed.add(entity);
      }
    }
    if (!allowed.isEmpty()) {
      IOUtils.deleteFile(allowed);
      List<Long> allowedIds = allowed.stream().map(ApprovalAttachmentEntity::getId).toList();
      baseMapper.deleteBatchIds(allowedIds);
      result.setDeletedIds(allowedIds);
    }
    return result;
  }

  @Override
  public Set<Long> getRelatedRecordIds(List<Long> attachmentIds, String modelName) {
    Set<Long> result = Collections.emptySet();
    if (attachmentIds != null && !attachmentIds.isEmpty()) {
      result =
          baseMapper
              .selectList(
                  new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                      .in(ApprovalAttachmentEntity::getId, attachmentIds)
                      .eq(ApprovalAttachmentEntity::getModelName, modelName))
              .stream()
              .map(ApprovalAttachmentEntity::getAndId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
    }
    return result;
  }

  @Override
  public byte[] downloadAttachment(Long attachmentId) {
    if (attachmentId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    ApprovalAttachmentEntity entity = baseMapper.selectById(attachmentId);
    if (entity == null) {
      throw new BaseException(ErrorCode.DATA_NULL);
    }

    // 使用 IOUtils 读取文件内容（包含二进制数据）
    ApprovalAttachmentVO vo = IOUtils.getOneFileBytes(entity);

    if (vo == null || vo.getFileData() == null) {
      throw new BaseException(ErrorCode.FILE_READ_FAILED);
    }

    return vo.getFileData();
  }

  @Override
  public ApprovalAttachmentEntity getEntityById(Long attachmentId) {
    ApprovalAttachmentEntity result = null;
    if (attachmentId != null) {
      result = baseMapper.selectById(attachmentId);
    }
    return result;
  }

  @Override
  public List<ApprovalAttachmentEntity> listEntitiesByAndIds(List<Long> andIds, String modelName) {
    List<ApprovalAttachmentEntity> result = Collections.emptyList();
    if (andIds != null && !andIds.isEmpty()) {
      result =
          baseMapper.selectList(
              new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                  .in(ApprovalAttachmentEntity::getAndId, andIds)
                  .eq(ApprovalAttachmentEntity::getModelName, modelName));
    }
    return result;
  }
}
