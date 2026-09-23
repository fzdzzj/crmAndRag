package com.slz.crm.common.untils;

import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.ChunkMergeDTO;
import com.slz.crm.pojo.dto.ChunkUploadDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.vo.ChunkStatusVO;
import com.slz.crm.server.constant.MessageConstant;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 分片上传存储支持类：分片保存/状态检查/合并/清理，与 {@link IOUtils} 共享文件根路径配置。 */
public class ChunkStorageUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ChunkStorageUtils.class);

  private ChunkStorageUtils() {}

  /**
   * 获取分片存储目录
   *
   * @param fileIdentifier 文件唯一标识
   * @return 分片存储目录
   */
  private static File getChunkDir(String fileIdentifier) {
    return new File(
        IOUtils.filePath
            + IOUtils.FILE_SEPARATOR
            + "chunks"
            + IOUtils.FILE_SEPARATOR
            + fileIdentifier);
  }

  /**
   * 获取分片文件
   *
   * @param fileIdentifier 文件唯一标识
   * @param chunkIndex 分片索引
   * @return 分片文件
   */
  private static File getChunkFile(String fileIdentifier, Integer chunkIndex) {
    return new File(getChunkDir(fileIdentifier), "chunk_" + chunkIndex);
  }

  /**
   * 保存分片
   *
   * @param dto 分片上传DTO
   * @return 是否成功
   */
  public static Boolean saveChunk(ChunkUploadDTO dto) {
    try {
      if (dto.getFileIdentifier() == null
          || dto.getChunkIndex() == null
          || dto.getChunkFile() == null) {
        throw new ServiceException(MessageConstant.FILE_APPROVAL_ID_ERROR);
      }

      File chunkDir = getChunkDir(dto.getFileIdentifier());
      if (!chunkDir.exists()) {
        boolean mkdirsSuccess = chunkDir.mkdirs();
        if (!mkdirsSuccess) {
          throw new IOException("无法创建分片目录: " + chunkDir.getAbsolutePath());
        }
      }

      File chunkFile = getChunkFile(dto.getFileIdentifier(), dto.getChunkIndex());
      dto.getChunkFile().transferTo(chunkFile);
      return true;
    } catch (IOException e) {
      throw new ServiceException(MessageConstant.FILE_CREATE_ERROR, e);
    }
  }

  /**
   * 检查分片状态
   *
   * @param fileIdentifier 文件唯一标识
   * @param totalChunks 总分片数
   * @return 分片状态VO
   */
  public static ChunkStatusVO checkChunkStatus(String fileIdentifier, Integer totalChunks) {
    ChunkStatusVO vo = new ChunkStatusVO();
    vo.setFileIdentifier(fileIdentifier);

    File chunkDir = getChunkDir(fileIdentifier);
    List<Integer> uploadedChunks = new ArrayList<>();

    if (chunkDir.exists() && chunkDir.isDirectory()) {
      File[] chunkFiles = chunkDir.listFiles((dir, name) -> name.startsWith("chunk_"));
      if (chunkFiles != null) {
        uploadedChunks =
            Arrays.stream(chunkFiles)
                .map(
                    file -> {
                      String name = file.getName();
                      try {
                        return Integer.parseInt(name.substring(6)); // "chunk_" 长度为6
                      } catch (NumberFormatException e) {
                        return -1;
                      }
                    })
                .filter(index -> index >= 0)
                .sorted()
                .collect(Collectors.toList());
      }
    }

    vo.setUploadedChunks(uploadedChunks);
    vo.setIsComplete(totalChunks != null && uploadedChunks.size() == totalChunks);
    return vo;
  }

  /**
   * 合并分片
   *
   * @param dto 分片合并DTO
   * @return 文件实体
   */
  public static ApprovalAttachmentEntity mergeChunks(ChunkMergeDTO dto) {
    try {
      if (dto.getAndId() == null) {
        throw new ServiceException(MessageConstant.FILE_APPROVAL_ID_ERROR);
      }

      File chunkDir = getChunkDir(dto.getFileIdentifier());
      if (!chunkDir.exists() || !chunkDir.isDirectory()) {
        throw new ServiceException(MessageConstant.CHUNK_DIR_NOT_EXIST);
      }

      // 检查所有分片是否都存在
      assertAllChunksExist(dto);

      // 创建目标文件
      File targetFile = prepareMergeTargetFile(dto);

      // 合并分片
      writeMergedChunks(dto, targetFile);

      return buildMergedEntity(dto, targetFile);
    } catch (IOException e) {
      throw new ServiceException(MessageConstant.FILE_CREATE_ERROR, e);
    }
  }

  /** 校验全部分片文件是否都已上传，缺任一片即报错 */
  private static void assertAllChunksExist(ChunkMergeDTO dto) {
    for (int i = 0; i < dto.getTotalChunks(); i++) {
      File chunkFile = getChunkFile(dto.getFileIdentifier(), i);
      if (!chunkFile.exists()) {
        throw new ServiceException(String.format(MessageConstant.CHUNK_FILE_NOT_EXIST, i));
      }
    }
  }

  /** 根据合并 DTO 构造目标文件对象，父目录不存在时递归创建 */
  private static File prepareMergeTargetFile(ChunkMergeDTO dto) throws IOException {
    ApprovalAttachmentDTO attachmentDTO = new ApprovalAttachmentDTO();
    attachmentDTO.setAndId(dto.getAndId());
    attachmentDTO.setModelName(dto.getModelName());
    attachmentDTO.setFileName(dto.getFileName());
    attachmentDTO.setFileType(dto.getFileType());

    File targetFile = IOUtils.getFileByDTO(attachmentDTO);
    File parentDir = targetFile.getParentFile();
    if (!parentDir.exists()) {
      boolean mkdirsSuccess = parentDir.mkdirs();
      if (!mkdirsSuccess) {
        throw new IOException("无法创建目录: " + parentDir.getAbsolutePath());
      }
    }
    return targetFile;
  }

  /** 按分片顺序将各分片内容顺序写入目标文件 */
  private static void writeMergedChunks(ChunkMergeDTO dto, File targetFile) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
      for (int i = 0; i < dto.getTotalChunks(); i++) {
        File chunkFile = getChunkFile(dto.getFileIdentifier(), i);
        try (FileInputStream fis = new FileInputStream(chunkFile)) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = fis.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
          }
        }
      }
    }
  }

  /** 由合并 DTO 与实际落盘文件构建附件实体（文件大小优先取 DTO 声明值，否则取实际大小） */
  private static ApprovalAttachmentEntity buildMergedEntity(ChunkMergeDTO dto, File targetFile) {
    // 创建文件实体
    ApprovalAttachmentEntity entity = new ApprovalAttachmentEntity();
    entity.setAndId(dto.getAndId());
    entity.setModelName(dto.getModelName());
    entity.setFileName(dto.getFileName());
    entity.setFileType(dto.getFileType());
    // 优先使用实际文件大小，如果totalSize为null则使用合并后的文件大小
    if (dto.getTotalSize() != null && dto.getTotalSize() > 0) {
      entity.setFileSize(dto.getTotalSize());
    } else {
      // 从合并后的文件获取实际大小
      long actualFileSize = targetFile.length();
      entity.setFileSize(actualFileSize);
    }

    String modelName = dto.getModelName() != null ? dto.getModelName() : "default";
    entity.setFilePath(
        IOUtils.filePath
            + IOUtils.FILE_SEPARATOR
            + modelName
            + IOUtils.FILE_SEPARATOR
            + dto.getAndId()
            + IOUtils.FILE_SEPARATOR);

    return entity;
  }

  /**
   * 删除分片目录
   *
   * @param fileIdentifier 文件唯一标识
   * @return 是否成功
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 分片目录删除边界：文件系统操作多源异常，统一转 ServiceException
  public static Boolean deleteChunks(String fileIdentifier) {
    try {
      File chunkDir = getChunkDir(fileIdentifier);
      if (chunkDir.exists() && chunkDir.isDirectory()) {
        File[] files = chunkDir.listFiles();
        List<String> failedFiles = new ArrayList<>();
        if (files != null) {
          for (File file : files) {
            if (!file.delete()) {
              failedFiles.add(file.getName());
            }
          }
        }
        boolean dirDeleted = true;
        if (failedFiles.isEmpty()) {
          // 全部删除成功后才尝试删除目录
          dirDeleted = chunkDir.delete();
        } else {
          LOG.warn("分片删除失败，保留目录: {} -> {}", chunkDir.getAbsolutePath(), failedFiles);
        }
        if (!failedFiles.isEmpty() || !dirDeleted) {
          StringBuilder msg = new StringBuilder();
          for (String name : failedFiles) {
            msg.append(MessageConstant.CHUNK_FILE_DELETE_ERROR).append(name).append("; ");
          }
          if (!dirDeleted) {
            msg.append(MessageConstant.CHUNK_DIR_DELETE_ERROR);
          }
          throw new ServiceException(msg.toString());
        }
      }
      return true;
    } catch (Exception e) {
      throw new ServiceException(MessageConstant.FILE_DELETE_ERROR, e);
    }
  }
}
