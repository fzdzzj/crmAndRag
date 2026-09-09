package com.slz.crm.unit.controller;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.server.controller.PublicAttachmentController;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.AttachmentAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicAttachmentControllerTest {

    private static final String JWT_SECRET = "12345678901234567890123456789012";

    @Mock private AttachmentDownloadTokenUtil downloadTokenUtil;
    @Mock private ApprovalAttachmentMapper attachmentMapper;
    @Mock private ProjectFileMapper projectFileMapper;
    @Mock private AttachmentAccessService attachmentAccessService;
    @Mock private JwtProperties jwtProperties;
    @Mock private HttpServletRequest httpRequest;
    @Mock private HttpServletResponse response;
    @InjectMocks private PublicAttachmentController controller;

    @BeforeEach
    void setUp() throws IOException {
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    @DisplayName("下载令牌签发用户与当前 JWT 用户不一致时不查询附件")
    void rejectsMismatchedTokenUserBeforeReadingAttachment() {
        when(downloadTokenUtil.parseDownloadToken("token"))
                .thenReturn(downloadToken(10L, 2L, "approval_attachment", "business_activity"));
        authenticateAs(3L);

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verifyNoInteractions(attachmentMapper, projectFileMapper, attachmentAccessService);
    }

    @Test
    @DisplayName("过期下载令牌不会查询附件或读取文件")
    void rejectsExpiredTokenBeforeReadingAttachment() {
        when(downloadTokenUtil.parseDownloadToken("expired"))
                .thenThrow(new IllegalArgumentException("下载链接已过期"));

        controller.downloadAttachment("expired", response);

        verify(response).setStatus(401);
        verifyNoInteractions(attachmentMapper, projectFileMapper, attachmentAccessService);
    }

    @Test
    @DisplayName("令牌模型与真实附件模型不一致时拒绝下载")
    void rejectsTokenWithDifferentAttachmentModel() throws IOException {
        when(downloadTokenUtil.parseDownloadToken("token"))
                .thenReturn(downloadToken(10L, 2L, "approval_attachment", "business_activity"));
        authenticateAs(2L);
        when(attachmentMapper.selectById(10L)).thenReturn(attachment(10L, "contact_task"));

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verify(attachmentMapper).selectById(10L);
        verifyNoInteractions(attachmentAccessService);
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("下载时记录级授权被撤销后不读取文件")
    void doesNotReadFileWhenCurrentRecordAccessIsDenied() throws IOException {
        ApprovalAttachmentEntity attachment = attachment(10L, "business_activity");
        when(downloadTokenUtil.parseDownloadToken("token"))
                .thenReturn(downloadToken(10L, 2L, "approval_attachment", "business_activity"));
        authenticateAs(2L);
        when(attachmentMapper.selectById(10L)).thenReturn(attachment);
        doThrow(new BaseException("无权读取该业务活动附件"))
                .when(attachmentAccessService).assertCanRead(attachment, 2L);

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verify(attachmentAccessService).assertCanRead(attachment, 2L);
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("终态协助历史令牌使用快照授权，不回退到实时记录授权")
    void usesHistoricalAssistAuthorizationForHistoricalToken() throws IOException {
        ApprovalAttachmentEntity attachment = attachment(10L, "assist_request");
        AttachmentDownloadTokenUtil.DownloadToken token = downloadToken(
                10L, 2L, "approval_attachment", "assist_request");
        token.setHistoricalAssistId(20L);
        when(downloadTokenUtil.parseDownloadToken("token")).thenReturn(token);
        authenticateAs(2L);
        when(attachmentMapper.selectById(10L)).thenReturn(attachment);
        doThrow(new BaseException("附件不属于该协助历史快照"))
                .when(attachmentAccessService).assertCanReadHistorical(attachment, 20L, 2L);

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verify(attachmentAccessService).assertCanReadHistorical(attachment, 20L, 2L);
        verify(attachmentAccessService, never()).assertCanRead(attachment, 2L);
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("实时协助来源令牌走 assistId 校验，不回退到普通附件权限")
    void usesAssistSourceAuthorizationForActiveAssistToken() throws IOException {
        ApprovalAttachmentEntity attachment = attachment(10L, "business_activity");
        attachment.setAndId(100L);
        AttachmentDownloadTokenUtil.DownloadToken token = downloadToken(
                10L, 2L, "approval_attachment", "business_activity");
        token.setActiveAssistId(20L);
        when(downloadTokenUtil.parseDownloadToken("token")).thenReturn(token);
        authenticateAs(2L);
        when(attachmentMapper.selectById(10L)).thenReturn(attachment);
        doThrow(new BaseException("协助已结束"))
                .when(attachmentAccessService).assertCanReadAssistSource(attachment, 20L, 2L);

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verify(attachmentAccessService).assertCanReadAssistSource(attachment, 20L, 2L);
        verify(attachmentAccessService, never()).assertCanRead(attachment, 2L);
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("未知文件类型令牌不会触发任意附件查询")
    void rejectsUnknownFileTypeBeforeReadingAttachment() {
        when(downloadTokenUtil.parseDownloadToken("token"))
                .thenReturn(downloadToken(10L, 2L, "unknown", null));
        authenticateAs(2L);

        controller.downloadAttachment("token", response);

        verify(response).setStatus(400);
        verifyNoInteractions(attachmentMapper, projectFileMapper, attachmentAccessService);
    }

    private void authenticateAs(Long userId) {
        when(jwtProperties.getTokenName()).thenReturn("token");
        when(jwtProperties.getSecretKey()).thenReturn(JWT_SECRET);
        String jwt = JwtUntil.createJWT(JWT_SECRET, 60_000L, Map.of("userID", userId));
        when(httpRequest.getHeader("token")).thenReturn(jwt);
    }

    private AttachmentDownloadTokenUtil.DownloadToken downloadToken(
            Long attachmentId, Long userId, String fileType, String modelName) {
        AttachmentDownloadTokenUtil.DownloadToken token = new AttachmentDownloadTokenUtil.DownloadToken();
        token.setAttachmentId(attachmentId);
        token.setUserId(userId);
        token.setFileType(fileType);
        token.setModelName(modelName);
        return token;
    }

    private ApprovalAttachmentEntity attachment(Long id, String modelName) {
        ApprovalAttachmentEntity attachment = new ApprovalAttachmentEntity();
        attachment.setId(id);
        attachment.setModelName(modelName);
        return attachment;
    }
}
