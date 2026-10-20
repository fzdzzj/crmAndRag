package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.ProjectFileService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ProjectFileServiceImpl extends ServiceImpl<ProjectFileMapper, ProjectFileEntity>
    implements ProjectFileService {

  @Autowired private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Autowired private HttpServletRequest request;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private ContractOrderItemMapper contractOrderItemMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private AttachmentAccessService attachmentAccessService;

  @Autowired private UserMapper userMapper;

  /** 阶段二落库的短事务管理器（A-5：磁盘 IO 已移出事务，仅批量 INSERT 走此事务）。 */
  @Autowired private PlatformTransactionManager transactionManager;

  @Value("${slz.file.path}")
  private String basePath;

  @Override
  public void uploadByActivity(Long activityId, List<ProjectFileDTO> dtoList) {
    uploadInternal(
        dtoList,
        new UploadHost(activityId, "业务活动 " + activityId, UploadHost.NO_PRE_WRITE_CHECK) {
          private BusinessActivityEntity activity;

          @Override
          void checkHostAndPermission() {
            // 查询业务活动信息，获取关联的销售机会ID
            activity = businessActivityMapper.selectById(activityId);
            if (activity == null) {
              throw new BaseException(ErrorCode.DATA_NULL, "业务活动不存在");
            }
            // 记录级写权限：仅业务相关人可向该活动上传附件，越权拦截（12002）
            if (!attachmentAccessService.canWriteAttachments(
                ModelName.BUSINESS_ACTIVITY, activityId, BaseUnit.getCurrentId())) {
              throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权向该业务活动上传附件");
            }
          }

          @Override
          void decorate(ProjectFileDTO dto, ProjectFileEntity entity) {
            entity.setActivityId(activityId);
            entity.setOpportunityId(activity.getOpportunityId());
          }
        });
  }

  @Override
  public void uploadByOrder(Long orderId, List<ProjectFileDTO> dtoList) {
    uploadInternal(
        dtoList,
        new UploadHost(orderId, "订单项 " + orderId, UploadHost.NO_PRE_WRITE_CHECK) {
          private ContractOrderItemEntity orderItem;

          @Override
          void checkHostAndPermission() {
            // 查询订单项信息，获取关联合同ID
            orderItem = contractOrderItemMapper.selectById(orderId);
            if (orderItem == null) {
              throw new BaseException(ErrorCode.DATA_NULL, "订单项不存在");
            }
            // 记录级写权限：订单项无属主字段，按合同归属判定，越权拦截（12002）
            if (!attachmentAccessService.canWriteAttachments(
                ModelName.CONTRACT_ORDER_ITEM, orderId, BaseUnit.getCurrentId())) {
              throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权向该订单上传附件");
            }
          }

          @Override
          void decorate(ProjectFileDTO dto, ProjectFileEntity entity) {
            entity.setContractId(orderItem.getContractId());
            entity.setOrderId(orderId);
          }
        });
  }

  @Override
  public void uploadStandalone(List<ProjectFileDTO> dtoList) {
    uploadInternal(
        dtoList,
        new UploadHost(
            null,
            "独立文件区",
            () -> {
              // 先验证所有销售机会是否存在（独立上传无宿主记录，磁盘落到 standalone 目录）
              for (ProjectFileDTO dto : dtoList) {
                Long opportunityId = dto.getOpportunityId();
                if (opportunityId != null
                    && salesOpportunityMapper.selectById(opportunityId) == null) {
                  throw new BaseException(
                      ErrorCode.DATA_NULL, "销售机会不存在，opportunityId=" + opportunityId);
                }
              }
            }) {
          @Override
          void decorate(ProjectFileDTO dto, ProjectFileEntity entity) {
            entity.setOpportunityId(dto.getOpportunityId());
            entity.setContractId(dto.getContractId());
          }
        });
  }

  /**
   * 三上传方法唯一模板（A-5+A-16）。
   *
   * <p>阶段一（事务外）：宿主校验与权限闸通过后，把全部文件写盘，任一失败即清盘已写文件并上抛 FILE_READ_FAILED；阶段二： 短事务 {@code
   * TransactionTemplate} 内一次批量 INSERT，落库失败则事务回滚 + 清盘 + 上抛。磁盘 IO 已移出事务， 事务边界只覆盖落库；宿主校验/权限闸的
   * 12001/12002 错误码与文案与旧逐方法实现逐字一致。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件写盘+DB落库多源，失败回滚已传文件后上抛
  private void uploadInternal(List<ProjectFileDTO> dtoList, UploadHost host) {
    if (dtoList == null || dtoList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
    }

    ProjectFileStorageSupport.validateCategory(dtoList);

    host.checkHostAndPermission();

    Long currentUserId = BaseUnit.getCurrentId();

    // 记录已上传到磁盘的文件，用于失败时回滚
    List<File> uploadedFiles = new ArrayList<>();
    List<ProjectFileEntity> entities = new ArrayList<>(dtoList.size());

    try {
      // 阶段一：先将所有文件写入磁盘（事务外；独立上传的销售机会校验也在此阶段）
      host.getPreWriteCheck().run();
      for (ProjectFileDTO dto : dtoList) {
        ProjectFileEntity entity =
            ProjectFileStorageSupport.writeFileToDisk(dto, host.getRelatedId(), basePath);
        entity.setCategory(dto.getCategory());
        entity.setTheme(dto.getTheme());
        entity.setDescription(dto.getDescription());
        entity.setUploaderId(currentUserId);
        entity.setUploadTime(LocalDateTime.now());
        host.decorate(dto, entity);
        entities.add(entity);

        // 记录已上传的文件，用于失败回滚
        uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
      }
    } catch (Exception e) {
      log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(uploadedFiles);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
    }

    try {
      // 阶段二：短事务内一次批量落库（磁盘 IO 不在事务内）
      transactionTemplate().executeWithoutResult(status -> baseMapper.insertBatch(entities));
    } catch (Exception e) {
      log.error("上传文件落库失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(uploadedFiles);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
    }

    log.info("成功上传 {} 个文件到 {}", dtoList.size(), host.getTargetLabel());
  }

  /** 阶段二短事务：仅覆盖批量落库，磁盘 IO 不进事务（A-5 事务边界迁移）。 */
  private TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  /** 上传宿主注入点：宿主存在性/权限闸、写盘前校验、实体字段装饰三处差异由宿主提供。 */
  private abstract static class UploadHost {

    /** 无写盘前校验的宿主占位（业务活动/订单项）。 */
    private static final Runnable NO_PRE_WRITE_CHECK = () -> {};

    private final Long relatedId;
    private final String targetLabel;
    private final Runnable preWriteCheck;

    UploadHost(Long relatedId, String targetLabel, Runnable preWriteCheck) {
      this.relatedId = relatedId;
      this.targetLabel = targetLabel;
      this.preWriteCheck = preWriteCheck;
    }

    Long getRelatedId() {
      return relatedId;
    }

    String getTargetLabel() {
      return targetLabel;
    }

    Runnable getPreWriteCheck() {
      return preWriteCheck;
    }

    /** 宿主存在性与记录级写权限闸：在任何落盘前上抛，错误码与文案由各宿主保留（独立上传无宿主，默认空实现）。 */
    void checkHostAndPermission() {}

    /** 实体字段装饰：宿主关联字段（activityId+opportunityId / contractId+orderId / DTO 直填）。 */
    abstract void decorate(ProjectFileDTO dto, ProjectFileEntity entity);
  }

  @Override
  public Page<ProjectFileVO> queryPage(
      Integer pageNum, Integer pageSize, ProjectFileQueryDTO queryDTO) {
    Page<ProjectFileEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<ProjectFileEntity> wrapper =
        ProjectFileStorageSupport.buildQueryWrapper(queryDTO);
    wrapper.orderByDesc(ProjectFileEntity::getUploadTime);

    Page<ProjectFileEntity> entityPage = baseMapper.selectPage(page, wrapper);

    // 记录级数据范围：仅返回当前用户可读的文件；total 保留库内总数仅用于分页控件，越权行不返回
    Page<ProjectFileVO> voPage = new Page<>(pageNum, pageSize, entityPage.getTotal());
    voPage.setRecords(toReadableVOs(entityPage.getRecords()));
    return voPage;
  }

  @Override
  public List<ProjectFileVO> listByActivityId(Long activityId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getActivityId, activityId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return toReadableVOs(entities);
  }

  @Override
  public List<ProjectFileVO> listByOrderId(Long orderId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getOrderId, orderId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return toReadableVOs(entities);
  }

  @Override
  public List<ProjectFileVO> listByContractId(Long contractId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getContractId, contractId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return toReadableVOs(entities);
  }

  @Override
  public List<ProjectFileVO> listByOpportunityId(Long opportunityId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getOpportunityId, opportunityId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return toReadableVOs(entities);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteByIds(List<Long> ids) {
    if (ids != null && !ids.isEmpty()) {
      // 1. 先查询文件信息（用于后续删除磁盘文件）
      List<ProjectFileEntity> entities = baseMapper.selectBatchIds(ids);
      if (entities != null && !entities.isEmpty()) {
        ProjectFileStorageSupport.doDelete(ids, entities, baseMapper, userMapper);
      }
    }
  }

  @Override
  public ProjectFileEntity getEntityById(Long id) {
    ProjectFileEntity result;
    if (id == null) {
      result = null;
    } else {
      result = baseMapper.selectById(id);
    }
    return result;
  }

  /**
   * optimize-project-file-list-auth-reuse 任务 3.1：列表链路统一「判定一次 + 已知可读转换」私有链。
   *
   * <p>batch-project-file-list-auth-reads 任务 3.2：判定改走批量过滤入口 {@code filterReadableProjectFiles}
   * （同一请求恰好调用 1 次），批量内部整组 1 次 {@code sys_user} 读 + 维度实体/参与人预取 + 与单行入口
   * 逐字一致的逐行判定矩阵；权限链逐行实时语义不变。过滤语义不变（不可读行被丢弃、保持原顺序）， 保留行以已知可读前提转换 VO 并直接签发下载令牌。下载令牌只是便利性输出，真实下载 由
   * PublicAttachmentController 逐次独立复核，本复用严格限于单请求内、零跨请求角色/权限缓存。
   */
  private List<ProjectFileVO> toReadableVOs(List<ProjectFileEntity> entities) {
    List<ProjectFileVO> voList = new ArrayList<>();
    if (!entities.isEmpty()) {
      Long currentUserId = BaseUnit.getCurrentId();
      List<ProjectFileEntity> readable =
          attachmentAccessService.filterReadableProjectFiles(entities, currentUserId);
      for (ProjectFileEntity entity : readable) {
        voList.add(toKnownReadableVO(entity, currentUserId));
      }
    }
    return voList;
  }

  /** 已知可读行的 VO 转换：上传人姓名转换照旧；下载令牌直接签发（可读性判定已由调用方完成）。 */
  private ProjectFileVO toKnownReadableVO(ProjectFileEntity entity, Long currentUserId) {
    ProjectFileVO vo = new ProjectFileVO();
    BeanUtils.copyProperties(entity, vo);

    // 查询上传人姓名
    if (entity.getUploaderId() != null) {
      vo.setUploaderName(dataConvertService.getUserName(entity.getUploaderId()));
    }

    String baseUrl = ProjectFileStorageSupport.getBaseUrl(request);
    String token =
        downloadTokenUtil.generateDownloadToken(entity.getId(), currentUserId, "project_file");
    vo.setDownloadUrl(baseUrl + "/public/attachment/download?token=" + token);
    return vo;
  }
}
