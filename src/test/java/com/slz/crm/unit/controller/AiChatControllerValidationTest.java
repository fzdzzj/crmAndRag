package com.slz.crm.unit.controller;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.controller.AiChatController;
import com.slz.crm.server.handler.GlobalExceptionHandler;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.pojo.dto.AiChatRequestDTO;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiChatImageService;
import com.slz.crm.server.service.AiChatService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 对话输入与分页校验")
class AiChatControllerValidationTest {

    @Mock
    private AiChatService aiChatService;

    @Mock
    private AiSessionService aiSessionService;

    @Mock
    private AiMessageService aiMessageService;

    @Mock
    private AiChatImageService aiChatImageService;

    private MockMvc mockMvc;

    private AiChatController controller;

    @BeforeEach
    void setUp() {
        RoleAO user = new RoleAO();
        user.setId(42L);
        BaseUnit.setCurrentRole(user);
        controller = new AiChatController();
        ReflectionTestUtils.setField(controller, "aiChatService", aiChatService);
        ReflectionTestUtils.setField(controller, "aiSessionService", aiSessionService);
        ReflectionTestUtils.setField(controller, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(controller, "aiChatImageService", aiChatImageService);
        ReflectionTestUtils.setField(controller, "aiProperties", new AiProperties());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        BaseUnit.removeCurrentId();
    }

    @Test
    void streamChat_rejectsBlankMessageWithoutCallingService() throws Exception {
        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50002))
                .andExpect(jsonPath("$.msg").value("参数校验失败"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void streamChat_rejectsOverlongMessageWithoutCallingService() throws Exception {
        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "好".repeat(4001) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50002))
                .andExpect(jsonPath("$.msg").value("参数校验失败"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void listSessions_rejectsLimitAboveMaximum() throws Exception {
        mockMvc.perform(get("/ai/sessions").param("limit", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50007))
                .andExpect(jsonPath("$.msg").value("limit 必须在 1 到 100 之间"));

        verifyNoInteractions(aiSessionService);
    }

    @Test
    void listMessages_rejectsLimitBelowMinimum() throws Exception {
        mockMvc.perform(get("/ai/sessions/9/messages").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50007))
                .andExpect(jsonPath("$.msg").value("limit 必须在 1 到 100 之间"));

        verifyNoInteractions(aiSessionService, aiMessageService);
    }

    @Test
    void listSessions_acceptsDefaultLimit() throws Exception {
        when(aiSessionService.scrollSessions(42L, null, null, 10)).thenReturn(List.of());

        mockMvc.perform(get("/ai/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void streamChat_usesConfiguredFiniteSseTimeout() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setSseTimeoutSeconds(45);
        ReflectionTestUtils.setField(controller, "aiProperties", properties);

        AiChatRequestDTO dto = new AiChatRequestDTO();
        dto.setMessage("你好");
        SseEmitter emitter = controller.streamChat(dto, null);

        assertThat(emitter.getTimeout()).isEqualTo(45_000L);
    }

    @Test
    void uploadImage_rejectsNonImageWithoutCallingSave() throws Exception {
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(new AiSessionEntity());

        mockMvc.perform(multipart("/ai/sessions/9/images").file(new MockMultipartFile(
                        "file", "note.txt", "text/plain", "text".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50003));

        verifyNoInteractions(aiChatImageService);
    }

    @Test
    void uploadImage_savesOwnedSessionImageAndReturnsRef() throws Exception {
        AiChatImageEntity image = new AiChatImageEntity();
        image.setId(7L);
        image.setImageHash("a".repeat(64));
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(new AiSessionEntity());
        when(aiChatImageService.save(eq(9L), eq(42L), eq("image/png"), any())).thenReturn(image);

        mockMvc.perform(multipart("/ai/sessions/9/images").file(new MockMultipartFile(
                        "file", "picture.png", "image/png", "image".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.imageId").value(7))
                .andExpect(jsonPath("$.data.imageHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.imageRef").value("7"));

        verify(aiChatImageService).save(eq(9L), eq(42L), eq("image/png"), any());
    }
}
