package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
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
import org.springframework.transaction.annotation.Transactional;

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

  @Value("${slz.file.path}")
  private String basePath;

  @Override
  @Transactional(rollbackFor = Exception.class)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件写盘+DB落库多源，失败回滚已传文件后上抛
  public void uploadByActivity(Long activityId, List<ProjectFileDTO> dtoList) {

    if (dtoList == null || dtoList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
    }

    ProjectFileStorageSupport.validateCategory(dtoList);

    // 查询业务活动信息，获取关联的销售机会ID
    BusinessActivityEntity activity = businessActivityMapper.selectById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "业务活动不存在");
    }

    Long currentUserId = BaseUnit.getCurrentId();

    // 记录已上传到磁盘的文件，用于失败时回滚
    List<File> uploadedFiles = new ArrayList<>();

    try {
      // 1. 先将所有文件写入磁盘
      List<ProjectFileEntity> entities = new ArrayList<>();
      for (ProjectFileDTO dto : dtoList) {
        ProjectFileEntity entity =
            ProjectFileStorageSupport.writeFileToDisk(dto, activityId, basePath);
        entity.setCategory(dto.getCategory());
        entity.setTheme(dto.getTheme());
        entity.setDescription(dto.getDescription());
        entity.setUploaderId(currentUserId);
        entity.setUploadTime(LocalDateTime.now());
        entity.setActivityId(activityId);
        entity.setOpportunityId(activity.getOpportunityId());
        entities.add(entity);

        // 记录已上传的文件，用于失败回滚
        uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
      }

      // 2. 批量写入数据库
      for (ProjectFileEntity entity : entities) {
        baseMapper.insert(entity);
      }

      log.info("成功上传 {} 个文件到业务活动 {}", dtoList.size(), activityId);

    } catch (Exception e) {
      // 3. 失败时回滚：删除已上传的磁盘文件
      log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(uploadedFiles);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件写盘+DB落库多源，失败回滚已传文件后上抛
  public void uploadByOrder(Long orderId, List<ProjectFileDTO> dtoList) {

    if (dtoList == null || dtoList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
    }

    ProjectFileStorageSupport.validateCategory(dtoList);

    // 查询订单项信息，获取关联合同ID
    ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(orderId);
    if (orderItem == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "订单项不存在");
    }

    Long currentUserId = BaseUnit.getCurrentId();

    // 记录已上传到磁盘的文件，用于失败时回滚
    List<File> uploadedFiles = new ArrayList<>();

    try {
      // 1. 先将所有文件写入磁盘
      List<ProjectFileEntity> entities = new ArrayList<>();
      for (ProjectFileDTO dto : dtoList) {
        ProjectFileEntity entity =
            ProjectFileStorageSupport.writeFileToDisk(dto, orderId, basePath);
        entity.setCategory(dto.getCategory());
        entity.setTheme(dto.getTheme());
        entity.setDescription(dto.getDescription());
        entity.setUploaderId(currentUserId);
        entity.setUploadTime(LocalDateTime.now());
        entity.setContractId(orderItem.getContractId());
        entity.setOrderId(orderId);
        entities.add(entity);

        // 记录已上传的文件，用于失败回滚
        uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
      }

      // 2. 批量写入数据库（在事务中）
      for (ProjectFileEntity entity : entities) {
        baseMapper.insert(entity);
      }

      log.info("成功上传 {} 个文件到订单项 {}", dtoList.size(), orderId);

    } catch (Exception e) {
      // 3. 失败时回滚：删除已上传的磁盘文件
      log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(uploadedFiles);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件写盘+DB落库多源，失败回滚已传文件后上抛
  public void uploadStandalone(List<ProjectFileDTO> dtoList) {
    if (dtoList == null || dtoList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
    }

    ProjectFileStorageSupport.validateCategory(dtoList);

    Long currentUserId = BaseUnit.getCurrentId();

    // 记录已上传到磁盘的文件，用于失败时回滚
    List<File> uploadedFiles = new ArrayList<>();

    try {
      // 1. 先验证所有销售机会是否存在
      for (ProjectFileDTO dto : dtoList) {
        if (dto.getOpportunityId() != null) {
          SalesOpportunityEntity opportunity =
              salesOpportunityMapper.selectById(dto.getOpportunityId());
          if (opportunity == null) {
            throw new BaseException(
                ErrorCode.DATA_NULL, "销售机会不存在，opportunityId=" + dto.getOpportunityId());
          }
        }
      }

      // 2. 将所有文件写入磁盘
      List<ProjectFileEntity> entities = new ArrayList<>();
      for (ProjectFileDTO dto : dtoList) {
        ProjectFileEntity entity = ProjectFileStorageSupport.writeFileToDisk(dto, null, basePath);
        entity.setCategory(dto.getCategory());
        entity.setTheme(dto.getTheme());
        entity.setDescription(dto.getDescription());
        entity.setUploaderId(currentUserId);
        entity.setUploadTime(LocalDateTime.now());
        entity.setOpportunityId(dto.getOpportunityId());
        entity.setContractId(dto.getContractId());
        entities.add(entity);

        // 记录已上传的文件，用于失败回滚
        uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
      }

      // 3. 批量写入数据库（在事务中）
      for (ProjectFileEntity entity : entities) {
        baseMapper.insert(entity);
      }

      log.info("成功上传 {} 个独立文件", dtoList.size());

    } catch (Exception e) {
      // 4. 失败时回滚：删除已上传的磁盘文件
      log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
      ProjectFileStorageSupport.rollbackUploadedFiles(uploadedFiles);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
    }
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
   * <p>每行 {@code canReadProjectFile} 在同一请求内恰好判定 1 次完成过滤（过滤语义与原 filterReadable
   * 一致：不可读行被丢弃），保留行以已知可读前提转换 VO 并直接签发下载令牌， 不再对同一行二次调用授权服务，单请求判定调用 N+k → N。下载令牌只是便利性输出，真实下载 由
   * PublicAttachmentController 逐次独立复核，本复用严格限于单请求内、零跨请求角色/权限缓存。
   */
  private List<ProjectFileVO> toReadableVOs(List<ProjectFileEntity> entities) {
    Long currentUserId = BaseUnit.getCurrentId();
    List<ProjectFileVO> voList = new ArrayList<>(entities.size());
    for (ProjectFileEntity entity : entities) {
      if (attachmentAccessService.canReadProjectFile(entity, currentUserId)) {
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
