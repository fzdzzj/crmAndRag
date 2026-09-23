package com.slz.crm.common.untils;

import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.server.constant.MessageConstant;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class IOUtils {

  private static final Logger LOG = LoggerFactory.getLogger(IOUtils.class);

  // 存储位置
  static String filePath;

  @Value("${slz.file.path}")
  public void setFilePath(String filePath) {
    // 如果配置为空，使用默认值
    if (filePath == null || filePath.trim().isEmpty()) {
      LOG.warn("文件路径配置为空，使用默认值 ./file");
      filePath = "./file";
    }

    String processedPath = filePath.trim();

    // 如果路径以 ./ 开头，说明是相对路径，基于项目根目录
    if (processedPath.startsWith("./")) {
      // 获取项目根目录（jar 包所在目录或项目根目录）
      String projectRoot = System.getProperty("user.dir");

      // 去掉 ./ 前缀
      String relativePath = processedPath.substring(2);

      // 拼接项目根目录
      processedPath = projectRoot + File.separator + relativePath;
    }

    // 统一路径分隔符，确保在不同操作系统下都能正常工作
    processedPath = processedPath.replace("/", File.separator).replace("\\", File.separator);

    // 去除可能存在的重复分隔符
    processedPath = processedPath.replace(File.separator + File.separator, File.separator);

    // 去除末尾的分隔符（如果存在）
    if (processedPath.endsWith(File.separator)) {
      processedPath = processedPath.substring(0, processedPath.length() - 1);
    }

    IOUtils.filePath = processedPath;
  }

  // 文件/符号
  static final String FILE_SEPARATOR = File.separator;

  static File getFileByDTO(ApprovalAttachmentDTO dto) {

    if (dto.getAndId() == null || dto.getFileName() == null) {
      throw new ServiceException(MessageConstant.FILE_APPROVAL_ID_ERROR);
    }

    // 使用 modelName 创建子目录，实现不同模块文件的隔离
    String modelName = dto.getModelName() != null ? dto.getModelName() : "default";

    return new File(
        filePath
            + FILE_SEPARATOR
            + modelName
            + FILE_SEPARATOR
            + dto.getAndId()
            + FILE_SEPARATOR
            + dto.getFileName());
  }

  private static File getFilePathByDTO(ApprovalAttachmentDTO dto) {
    if (dto.getAndId() == null || dto.getFileName() == null) {
      throw new ServiceException(MessageConstant.FILE_APPROVAL_ID_ERROR);
    }

    // 使用 modelName 创建子目录，实现不同模块文件的隔离
    String modelName = dto.getModelName() != null ? dto.getModelName() : "default";

    return new File(
        filePath + FILE_SEPARATOR + modelName + FILE_SEPARATOR + dto.getAndId() + FILE_SEPARATOR);
  }

  private static File getFileByEntity(ApprovalAttachmentEntity entity) {
    if (entity.getAndId() == null || entity.getFileName() == null) {
      throw new ServiceException(MessageConstant.FILE_APPROVAL_ID_ERROR);
    }

    // 使用 modelName 创建子目录，实现不同模块文件的隔离
    String modelName = entity.getModelName() != null ? entity.getModelName() : "default";

    return new File(
        filePath
            + FILE_SEPARATOR
            + modelName
            + FILE_SEPARATOR
            + entity.getAndId()
            + FILE_SEPARATOR
            + entity.getFileName());
  }

  /**
   * 写入文件
   *
   * @param dto 文件信息
   * @return 文件实体
   */
  public static ApprovalAttachmentEntity writeFile(ApprovalAttachmentDTO dto) {

    // 检查 filePath 是否已初始化
    if (filePath == null) {
      throw new ServiceException("文件路径未初始化，请检查配置文件中的 slz.file.path 配置");
    }

    ApprovalAttachmentEntity attachment = new ApprovalAttachmentEntity();

    try {
      applyTimestampedFileName(dto);

      File file = getFileByDTO(dto);

      prepareTargetFile(file);

      MultipartFile fileData = dto.getFileData();
      if (fileData != null) {
        fileData.transferTo(file);
      }

      BeanUtils.copyProperties(dto, attachment);
      fillAttachmentMetadata(attachment, dto, fileData);

      return attachment;
    } catch (IOException e) {
      throw new ServiceException(MessageConstant.FILE_CREATE_ERROR, e);
    }
  }

  /** 生成带时间戳的文件名：fileName 为空时回退上传原始文件名，时间戳插在扩展名之前 */
  private static void applyTimestampedFileName(ApprovalAttachmentDTO dto) {
    // 如果 fileName 为空，使用上传文件的原始文件名
    if ((dto.getFileName() == null || dto.getFileName().trim().isEmpty())
        && dto.getFileData() != null
        && dto.getFileData().getOriginalFilename() != null) {
      dto.setFileName(dto.getFileData().getOriginalFilename());
    }

    // 在文件名后面添加时间戳后缀（在扩展名之前）
    String originalFileName = dto.getFileName();
    String fileExtension = "";
    String fileNameWithoutExt = originalFileName;

    // 分离文件扩展名
    int lastDotIndex = originalFileName.lastIndexOf(".");
    if (lastDotIndex > 0) {
      fileExtension = originalFileName.substring(lastDotIndex); // 包含点号，如 ".png"
      fileNameWithoutExt = originalFileName.substring(0, lastDotIndex);
    }

    // 生成时间戳（毫秒级）
    String timestamp = String.valueOf(System.currentTimeMillis());

    // 组合新文件名：原始文件名-时间戳.扩展名
    String newFileName = fileNameWithoutExt + "-" + timestamp + fileExtension;
    dto.setFileName(newFileName);
  }

  /** 准备目标文件位置：清理被文件占用的父目录路径、递归建目录、删除已存在的同名文件 */
  private static void prepareTargetFile(File file) throws IOException {
    File parentDir = file.getParentFile();

    // 检查父目录路径是否被一个文件占用了
    if (parentDir.exists() && parentDir.isFile()) {
      LOG.warn("父目录路径被一个文件占用了，将删除该文件: {}", parentDir.getAbsolutePath());
      boolean deleteSuccess = parentDir.delete();
      if (!deleteSuccess) {
        throw new IOException("无法删除占用父目录路径的文件: " + parentDir.getAbsolutePath());
      }
    }

    if (!parentDir.exists()) {
      // 递归创建多级目录
      boolean mkdirsSuccess = parentDir.mkdirs();
      if (!mkdirsSuccess) {
        throw new IOException("无法创建目录: " + parentDir.getAbsolutePath());
      }
      // 再次验证目录是否真的创建成功
      if (!parentDir.exists()) {
        throw new IOException("目录创建失败: " + parentDir.getAbsolutePath());
      }
    }

    // 如果文件已存在，先删除
    if (file.exists()) {
      boolean deleteSuccess = file.delete();
      if (!deleteSuccess) {
        throw new IOException("无法删除已存在的文件: " + file.getAbsolutePath());
      }
    }
  }

  /** 在实体上填充文件元数据：类型 / 路径 / 大小（有上传数据才填）与上传时间 */
  private static void fillAttachmentMetadata(
      ApprovalAttachmentEntity attachment, ApprovalAttachmentDTO dto, MultipartFile fileData) {
    // 设置文件类型
    if (fileData != null) {
      attachment.setFileType(fileData.getContentType());
    }
    // 设置文件路径
    if (fileData != null) {
      String modelName = dto.getModelName() != null ? dto.getModelName() : "default";
      attachment.setFilePath(
          filePath + FILE_SEPARATOR + modelName + FILE_SEPARATOR + dto.getAndId() + FILE_SEPARATOR);
    }
    // 设置文件大小
    if (fileData != null) {
      attachment.setFileSize(fileData.getSize());
    }
    // 设置上传时间
    attachment.setUploadTime(java.time.LocalDateTime.now());
  }

  /**
   * 删除文件
   *
   * @param entity 文件实体
   * @return 是否成功
   */
  public static Boolean deleteFile(ApprovalAttachmentEntity entity) {
    if (entity != null && entity.getFilePath() != null) {
      File file = getFileByEntity(entity);
      if (file.exists()) {
        if (!file.delete()) throw new ServiceException(MessageConstant.FILE_DELETE_ERROR);
      }
    }
    return true;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件删除边界：逐文件收集失败后统一抛聚合异常，需宽捕获
  public static Boolean deleteFile(List<ApprovalAttachmentEntity> entitys) {
    if (entitys != null && !entitys.isEmpty()) {
      List<String> failedFiles = new ArrayList<>();
      for (ApprovalAttachmentEntity entity : entitys) {
        try {
          deleteFile(entity);
        } catch (Exception e) {
          failedFiles.add(entity != null ? String.valueOf(entity.getFileName()) : "null");
          LOG.warn("删除文件失败: {}", failedFiles.get(failedFiles.size() - 1), e);
        }
      }
      if (!failedFiles.isEmpty()) {
        throw new ServiceException(
            MessageConstant.FILE_DELETE_ERROR + ": " + String.join(", ", failedFiles));
      }
    }
    return true;
  }

  /**
   * 获取文件字节
   *
   * @param entity 文件实体
   * @return 文件字节
   */
  public static ApprovalAttachmentVO getOneFileBytes(ApprovalAttachmentEntity entity) {

    try {
      byte[] bytes = Files.readAllBytes(getFileByEntity(entity).toPath());

      ApprovalAttachmentVO vo = new ApprovalAttachmentVO();

      vo.setFileData(bytes);
      BeanUtils.copyProperties(entity, vo);
      return vo;
    } catch (IOException e) {
      throw new ServiceException(MessageConstant.FILE_READ_ERROR, e);
    }
  }

  public static List<ApprovalAttachmentVO> readFile(List<ApprovalAttachmentEntity> entityList) {

    List<ApprovalAttachmentVO> voList = new ArrayList<>();
    if (entityList != null && !entityList.isEmpty()) {
      for (ApprovalAttachmentEntity entity : entityList) {
        ApprovalAttachmentVO vo = new ApprovalAttachmentVO();
        // 只复制元数据，不读取文件内容
        BeanUtils.copyProperties(entity, vo);
        voList.add(vo);
      }
    }

    return voList;
  }
}
