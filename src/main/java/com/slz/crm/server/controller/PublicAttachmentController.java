package com.slz.crm.server.controller;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.ProjectFileService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公开附件下载控制器 提供带AES加密令牌验证的公开附件下载接口（包含用户权限验证） */
@Slf4j
@RestController
@RequestMapping("/public/attachment")
public class PublicAttachmentController {

  @Autowired private AttachmentDownloadTokenUtil downloadTokenUtil;

  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  @Autowired private ProjectFileService projectFileService;

  @Autowired private AttachmentAccessService attachmentAccessService;

  @Autowired private JwtProperties jwtProperties;

  @Autowired private HttpServletRequest httpRequest;

  /**
   * 公开附件下载接口 通过AES加密令牌验证，限时30分钟，并验证当前登录用户与令牌用户一致 支持 ApprovalAttachment 和 ProjectFile 两种文件类型
   *
   * @param token 下载令牌（包含附件ID、用户ID和文件类型）
   * @param response HTTP响应
   */
  @GetMapping("/download")
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件下载HTTP边界：读写/校验多源，兜底转500
  public void downloadAttachment(
      @RequestParam("token") String token, HttpServletResponse response) {
    try {
      // 验证令牌并获取附件ID、用户ID和文件类型
      AttachmentDownloadTokenUtil.DownloadToken downloadToken =
          downloadTokenUtil.parseDownloadToken(token);
      Long attachmentId = downloadToken.getAttachmentId();
      Long tokenUserId = downloadToken.getUserId();
      String fileType = downloadToken.getFileType();

      // 从HTTP请求头中获取JWT Token，解析出当前登录用户ID
      Long currentUserId = requireCurrentUserId();

      // 校验当前登录用户与令牌用户一致，以及协助类令牌的文件类型约束
      verifyTokenContext(downloadToken, tokenUserId, currentUserId, fileType);

      // 根据文件类型查询对应的表
      if ("project_file".equals(fileType)) {
        downloadProjectFileByToken(
            downloadToken, attachmentId, tokenUserId, currentUserId, response);
      } else {
        downloadApprovalAttachmentByToken(
            downloadToken, attachmentId, tokenUserId, currentUserId, response);
      }

    } catch (BaseException e) {
      log.error("附件下载失败：{}", e.getMessage());
      handleError(response, e.getMessage(), 400);
    } catch (IllegalArgumentException e) {
      log.error("令牌验证失败：{}", e.getMessage());
      handleError(response, "下载链接已过期或无效", 401);
    } catch (Exception e) {
      log.error("附件下载异常", e);
      handleError(response, "下载失败，请稍后重试", 500);
    }
  }

  /** 从HTTP请求头解析 JWT 并取当前登录用户ID，令牌缺失或用户信息无效即抛错 */
  private Long requireCurrentUserId() {
    String jwtToken = httpRequest.getHeader(jwtProperties.getTokenName());
    if (jwtToken == null || jwtToken.isEmpty()) {
      throw new BaseException(ErrorCode.TOKEN_ERROR, "请先登录");
    }

    Claims claims = JwtUntil.parseJWT(jwtProperties.getSecretKey(), jwtToken);
    Long currentUserId = claims.get("userID", Long.class);

    if (currentUserId == null) {
      throw new BaseException(ErrorCode.TOKEN_ERROR, "用户信息无效");
    }
    return currentUserId;
  }

  /** 校验当前登录用户与令牌用户一致，以及历史/协助来源令牌的文件类型与互斥约束 */
  private void verifyTokenContext(
      AttachmentDownloadTokenUtil.DownloadToken downloadToken,
      Long tokenUserId,
      Long currentUserId,
      String fileType) {
    if (!currentUserId.equals(tokenUserId)) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "下载令牌与当前用户不匹配");
    }

    if (downloadToken.getHistoricalAssistId() != null && !"approval_attachment".equals(fileType)) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "历史附件令牌文件类型无效");
    }
    if (downloadToken.getActiveAssistId() != null && !"approval_attachment".equals(fileType)) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "协助来源附件令牌文件类型无效");
    }
    if (downloadToken.getHistoricalAssistId() != null
        && downloadToken.getActiveAssistId() != null) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "下载令牌上下文冲突");
    }
  }

  /** 项目文件下载分支：按当前权限记录级复核后落盘下载 */
  private void downloadProjectFileByToken(
      AttachmentDownloadTokenUtil.DownloadToken downloadToken,
      Long attachmentId,
      Long tokenUserId,
      Long currentUserId,
      HttpServletResponse response)
      throws IOException {
    ProjectFileEntity projectFileEntity = projectFileService.getEntityById(attachmentId);
    if (projectFileEntity == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "文件不存在");
    }
    // 记录级复核：令牌仅证明签发时的授权，实际下载必须按当前权限重新校验
    // （用户被移出业务/文件被重归属后，已签发但未过期的令牌同样会被拒绝）
    if (!attachmentAccessService.canReadProjectFile(projectFileEntity, currentUserId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权下载该项目文件");
    }
    downloadProjectFile(projectFileEntity, attachmentId, tokenUserId, response);
  }

  /** 审批附件下载分支：令牌 modelName 匹配 + 三种上下文（历史/协助来源/普通）的实时权限复核后落盘下载 */
  private void downloadApprovalAttachmentByToken(
      AttachmentDownloadTokenUtil.DownloadToken downloadToken,
      Long attachmentId,
      Long tokenUserId,
      Long currentUserId,
      HttpServletResponse response)
      throws IOException {
    if (!"approval_attachment".equals(downloadToken.getFileType())) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "下载令牌文件类型无效");
    }
    // 审批附件（默认）
    ApprovalAttachmentEntity approvalEntity = approvalAttachmentService.getEntityById(attachmentId);
    if (approvalEntity == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "附件不存在");
    }
    if (downloadToken.getModelName() != null
        && !downloadToken.getModelName().equals(approvalEntity.getModelName())) {
      throw new BaseException(ErrorCode.TOKEN_INVALID, "下载令牌与附件类型不匹配");
    }
    // 实时令牌重新校验当前业务记录权限；历史令牌只允许读取冻结快照中的附件。
    if (downloadToken.getHistoricalAssistId() != null) {
      attachmentAccessService.assertCanReadHistorical(
          approvalEntity, downloadToken.getHistoricalAssistId(), currentUserId);
    } else if (downloadToken.getActiveAssistId() != null) {
      attachmentAccessService.assertCanReadAssistSource(
          approvalEntity, downloadToken.getActiveAssistId(), currentUserId);
    } else {
      attachmentAccessService.assertCanRead(approvalEntity, currentUserId);
    }
    downloadApprovalAttachment(approvalEntity, attachmentId, tokenUserId, response);
  }

  /** 下载审批附件 */
  private void downloadApprovalAttachment(
      ApprovalAttachmentEntity entity, Long attachmentId, Long userId, HttpServletResponse response)
      throws IOException {
    log.info("用户 {} 请求下载审批附件 {}，文件名：{}", userId, attachmentId, entity.getFileName());

    response.setContentType(entity.getFileType());
    response.setContentLengthLong(entity.getFileSize());

    String encodedFileName =
        URLEncoder.encode(entity.getFileName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    response.setHeader(
        "Content-Disposition",
        "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);

    byte[] fileData = com.slz.crm.common.untils.IOUtils.getOneFileBytes(entity).getFileData();
    if (fileData == null || fileData.length == 0) {
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件读取失败");
    }

    try (OutputStream outputStream = response.getOutputStream()) {
      outputStream.write(fileData);
      outputStream.flush();
    }

    log.info("审批附件下载成功，附件ID：{}，用户ID：{}，文件名：{}", attachmentId, userId, entity.getFileName());
  }

  /** 下载项目文件 */
  private void downloadProjectFile(
      ProjectFileEntity entity, Long attachmentId, Long userId, HttpServletResponse response)
      throws IOException {
    log.info("用户 {} 请求下载项目文件 {}，文件名：{}", userId, attachmentId, entity.getFileName());

    response.setContentType(entity.getFileType());
    response.setContentLengthLong(entity.getFileSize());

    String encodedFileName =
        URLEncoder.encode(entity.getFileName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    response.setHeader(
        "Content-Disposition",
        "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);

    File file = new File(entity.getFilePath(), entity.getFileName());
    if (!file.exists()) {
      throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件不存在或已被删除");
    }

    try (FileInputStream fis = new FileInputStream(file);
        OutputStream outputStream = response.getOutputStream()) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = fis.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
      }
      outputStream.flush();
    }

    log.info("项目文件下载成功，文件ID：{}，用户ID：{}，文件名：{}", attachmentId, userId, entity.getFileName());
  }

  /** 处理错误响应 */
  private void handleError(HttpServletResponse response, String message, int status) {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    try {
      String jsonResponse =
          String.format("{\"code\": %d, \"message\": \"%s\", \"data\": null}", status, message);
      response.getWriter().write(jsonResponse);
      response.getWriter().flush();
    } catch (IOException e) {
      log.error("写入错误响应失败", e);
    }
  }
}
