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
import java.util.stream.Collectors;
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
    List<ProjectFileEntity> readable = filterReadable(entityPage.getRecords());

    Page<ProjectFileVO> voPage = new Page<>(pageNum, pageSize, entityPage.getTotal());
    List<ProjectFileVO> voList =
        readable.stream().map(this::entityToVO).collect(Collectors.toList());
    voPage.setRecords(voList);
    return voPage;
  }

  @Override
  public List<ProjectFileVO> listByActivityId(Long activityId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getActivityId, activityId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
  }

  @Override
  public List<ProjectFileVO> listByOrderId(Long orderId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getOrderId, orderId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
  }

  @Override
  public List<ProjectFileVO> listByContractId(Long contractId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getContractId, contractId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
  }

  @Override
  public List<ProjectFileVO> listByOpportunityId(Long opportunityId) {
    List<ProjectFileEntity> entities =
        baseMapper.selectList(
            new LambdaQueryWrapper<ProjectFileEntity>()
                .eq(ProjectFileEntity::getOpportunityId, opportunityId)
                .orderByDesc(ProjectFileEntity::getUploadTime));
    return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
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

  /** 记录级数据范围过滤：仅保留当前用户可读的项目文件（统一走附件授权入口）。 */
  private List<ProjectFileEntity> filterReadable(List<ProjectFileEntity> entities) {
    Long currentUserId = BaseUnit.getCurrentId();
    return entities.stream()
        .filter(e -> attachmentAccessService.canReadProjectFile(e, currentUserId))
        .collect(Collectors.toList());
  }

  /** Entity转VO */
  private ProjectFileVO entityToVO(ProjectFileEntity entity) {
    ProjectFileVO vo = new ProjectFileVO();
    BeanUtils.copyProperties(entity, vo);

    // 查询上传人姓名
    if (entity.getUploaderId() != null) {
      vo.setUploaderName(dataConvertService.getUserName(entity.getUploaderId()));
    }

    // 生成下载URL：无权读取该文件的行不签发令牌（downloadUrl 置空）
    if (attachmentAccessService.canReadProjectFile(entity, BaseUnit.getCurrentId())) {
      String baseUrl = ProjectFileStorageSupport.getBaseUrl(request);
      Long currentUserId = BaseUnit.getCurrentId();
      String token =
          downloadTokenUtil.generateDownloadToken(entity.getId(), currentUserId, "project_file");
      vo.setDownloadUrl(baseUrl + "/public/attachment/download?token=" + token);
    } else {
      log.warn("拦截越权项目文件下载链接签发：fileId={}, requester={}", entity.getId(), BaseUnit.getCurrentId());
    }

    return vo;
  }
}
