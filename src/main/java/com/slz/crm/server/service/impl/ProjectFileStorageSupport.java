package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.ProjectFileCategory;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.io.File;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目文件的磁盘读写与查询条件构建支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * ProjectFileServiceImpl，行为等价）。纯静态、无状态，basePath 等依赖经参数传入。
 */
@Slf4j
final class ProjectFileStorageSupport {

  private ProjectFileStorageSupport() {}

  /** 校验文件分类是否合法 */
  static void validateCategory(List<ProjectFileDTO> dtoList) {
    for (ProjectFileDTO dto : dtoList) {
      if (dto.getCategory() == null || !ProjectFileCategory.isValid(dto.getCategory())) {
        throw new BaseException(
            ErrorCode.PARAM_EMPTY,
            "文件分类不合法，必须为：VISIT_RECORD/MEETING_MINUTES/PROPOSAL/BID_DOCUMENT/PROJECT_CONTRACT");
      }
    }
  }

  /** 将文件写入磁盘，设置文件基本属性 */
  static ProjectFileEntity writeFileToDisk(ProjectFileDTO dto, Long relatedId, String basePath) {
    MultipartFile fileData = dto.getFileData();
    if (fileData == null || fileData.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件不能为空");
    }

    String originalFileName = fileData.getOriginalFilename();
    if (originalFileName == null || originalFileName.isEmpty()) {
      originalFileName = "unknown";
    }

    // 在文件名后添加时间戳
    String fileExtension = "";
    String fileNameWithoutExt = originalFileName;
    int lastDotIndex = originalFileName.lastIndexOf(".");
    if (lastDotIndex > 0) {
      fileExtension = originalFileName.substring(lastDotIndex);
      fileNameWithoutExt = originalFileName.substring(0, lastDotIndex);
    }
    String timestamp = String.valueOf(System.currentTimeMillis());
    String storedFileName = fileNameWithoutExt + "-" + timestamp + fileExtension;

    // 构建存储路径: basePath/modelName/relatedId/
    String dirPath;
    if (relatedId != null) {
      dirPath = basePath + File.separator + ModelName.PROJECT_FILE + File.separator + relatedId;
    } else {
      dirPath = basePath + File.separator + ModelName.PROJECT_FILE + File.separator + "standalone";
    }

    // 必须使用绝对路径：transferTo 遇到相对路径会解析到 Tomcat 临时目录，导致目录不存在而写入失败
    File dir = new File(dirPath).getAbsoluteFile();
    if (!dir.exists()) {
      boolean created = dir.mkdirs();
      log.info("创建项目文件目录: dir={}, created={}", dir.getPath(), created);
    }

    File targetFile = new File(dir, storedFileName);
    try {
      fileData.transferTo(targetFile);
    } catch (IOException e) {
      log.error(
          "项目文件写入失败: dir={}, fileName={}, dirExists={}, dirWritable={}",
          dirPath,
          storedFileName,
          dir.exists(),
          dir.canWrite(),
          e);
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件写入失败: " + e.getMessage());
    }

    ProjectFileEntity entity = new ProjectFileEntity();
    entity.setFileName(storedFileName);
    entity.setFilePath(dir.getPath() + File.separator);
    entity.setFileType(fileData.getContentType());
    entity.setFileSize(fileData.getSize());

    return entity;
  }

  /**
   * 回滚已上传的文件（删除磁盘文件）
   *
   * @param files 已上传的文件列表
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件删除回滚边界：失败仅记日志不中断
  static void rollbackUploadedFiles(List<File> files) {
    for (File file : files) {
      try {
        if (file.exists() && !file.delete()) {
          log.warn("回滚删除文件失败: {}", file.getAbsolutePath());
        }
      } catch (Exception e) {
        log.error("回滚删除文件异常: {}", file.getAbsolutePath(), e);
      }
    }
  }

  /** 从磁盘删除文件 */
  static void deleteFileFromDisk(ProjectFileEntity entity) {
    if (entity.getFilePath() != null && entity.getFileName() != null) {
      File file = new File(entity.getFilePath(), entity.getFileName());
      if (file.exists()) {
        if (!file.delete()) {
          throw new ServiceException(MessageConstant.FILE_DELETE_ERROR);
        }
      }
    }
  }

  /** 构建查询条件 */
  static LambdaQueryWrapper<ProjectFileEntity> buildQueryWrapper(ProjectFileQueryDTO queryDTO) {
    LambdaQueryWrapper<ProjectFileEntity> wrapper = new LambdaQueryWrapper<>();

    LambdaQueryWrapper<ProjectFileEntity> result;
    if (queryDTO == null) {
      result = wrapper;
    } else {

      wrapper
          .eq(
              queryDTO.getCategory() != null,
              ProjectFileEntity::getCategory,
              queryDTO.getCategory())
          .like(queryDTO.getTheme() != null, ProjectFileEntity::getTheme, queryDTO.getTheme())
          .like(
              queryDTO.getDescription() != null,
              ProjectFileEntity::getDescription,
              queryDTO.getDescription())
          .eq(
              queryDTO.getUploaderId() != null,
              ProjectFileEntity::getUploaderId,
              queryDTO.getUploaderId())
          .eq(
              queryDTO.getActivityId() != null,
              ProjectFileEntity::getActivityId,
              queryDTO.getActivityId())
          .eq(
              queryDTO.getOpportunityId() != null,
              ProjectFileEntity::getOpportunityId,
              queryDTO.getOpportunityId())
          .eq(
              queryDTO.getContractId() != null,
              ProjectFileEntity::getContractId,
              queryDTO.getContractId())
          .eq(queryDTO.getOrderId() != null, ProjectFileEntity::getOrderId, queryDTO.getOrderId())
          .ge(
              queryDTO.getMinUploadTime() != null,
              ProjectFileEntity::getUploadTime,
              queryDTO.getMinUploadTime())
          .le(
              queryDTO.getMaxUploadTime() != null,
              ProjectFileEntity::getUploadTime,
              queryDTO.getMaxUploadTime());
      result = wrapper;
    }
    return result;
  }

  /**
   * 删除主流程：主动删除分级校验 → 删库 → 删磁盘 → 失败告警（拆自 ProjectFileServiceImpl.doDelete，行为等价）。
   *
   * @param ids 待删除 ID 列表
   * @param entities 待删除实体
   * @param baseMapper 项目文件 mapper
   * @param userMapper 用户 mapper
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 批量删除磁盘文件边界：逐文件降级，失败仅列警告
  static void doDelete(
      List<Long> ids,
      List<ProjectFileEntity> entities,
      ProjectFileMapper baseMapper,
      UserMapper userMapper) {
    // 主动删除分级：上传人本人可删自己的；超管可删全部；其他拒绝
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && java.util.Objects.equals(currentUser.getRoleId(), 1L);
    for (ProjectFileEntity entity : entities) {
      if (isAdmin || java.util.Objects.equals(entity.getUploaderId(), currentId)) {
        continue;
      }
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "仅上传人本人或超管可删除该项目文件");
    }

    // 2. 先删除数据库记录
    int deletedCount = baseMapper.deleteBatchIds(ids);
    if (deletedCount != ids.size()) {
      throw new BaseException(
          ErrorCode.FILE_READ_FAILED, "删除失败: 期望删除" + ids.size() + "个，实际删除" + deletedCount + "个");
    }

    // 3. 再删除磁盘文件
    List<String> failedFiles = new java.util.ArrayList<>();
    for (ProjectFileEntity entity : entities) {
      try {
        deleteFileFromDisk(entity);
      } catch (Exception e) {
        log.error("删除磁盘文件失败: id={}, path={}", entity.getId(), entity.getFilePath(), e);
        failedFiles.add(entity.getFilePath() + entity.getFileName());
      }
    }

    // 4. 如果有删除失败的文件，记录警告日志
    if (!failedFiles.isEmpty()) {
      log.warn("以下文件磁盘删除失败，需要手动清理或等待定时任务处理: {}", failedFiles);
    }

    log.info("成功删除 {} 个文件", ids.size());
  }

  /** 获取基础URL（拆自 ProjectFileServiceImpl.getBaseUrl，行为等价）。 */
  static String getBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
    String contextPath = request.getContextPath();
    StringBuilder baseUrl = new StringBuilder();
    if (contextPath != null && !contextPath.isEmpty()) {
      baseUrl.append(contextPath);
    }
    return baseUrl.toString();
  }
}
