package com.slz.crm.unit.ai;

import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.server.ai.AiChatImageServiceImpl;
import com.slz.crm.server.ai.LocalAiChatImageStorage;
import com.slz.crm.server.mapper.AiChatImageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 聊天图片 hash 去重、会话隔离和本地可恢复字节验证。 */
class AiChatImageServiceImplTest {

    @TempDir
    Path tempDir;

    private AiChatImageMapper imageMapper;
    private LocalAiChatImageStorage storage;
    private AiChatImageServiceImpl service;

    @BeforeEach
    void setUp() {
        imageMapper = mock(AiChatImageMapper.class);
        storage = new LocalAiChatImageStorage(tempDir.toString());
        service = new AiChatImageServiceImpl(imageMapper, storage);
    }

    @Test
    void save_hashesContentAndPersistsStorageKey() {
        byte[] content = "image-bytes".getBytes();
        when(imageMapper.selectOne(any())).thenReturn(null);
        when(imageMapper.insert(any(AiChatImageEntity.class))).thenReturn(1);

        AiChatImageEntity saved = service.save(9L, 42L, "image/png", content);

        assertThat(saved.getImageHash()).hasSize(64).isEqualTo(
                LocalAiChatImageStorage.sha256Hex(content));
        assertThat(saved.getStorageBackend()).isEqualTo("local");
        assertThat(Files.exists(tempDir.resolve(saved.getStorageKey()))).isTrue();
        assertThat(storage.read(saved.getStorageKey())).isEqualTo(content);
    }

    @Test
    void findByRef_acceptsIdAndHashOnlyWithinSession() {
        AiChatImageEntity image = new AiChatImageEntity();
        image.setId(7L);
        image.setSessionId(9L);
        image.setImageHash("A".repeat(64));
        when(imageMapper.selectById(7L)).thenReturn(image);
        when(imageMapper.selectOne(any())).thenReturn(image);

        assertThat(service.findByRef(9L, "7")).contains(image);
        assertThat(service.findByRef(9L, "a".repeat(64))).contains(image);
        assertThat(service.findByRef(8L, "7")).isEmpty();
        assertThat(service.findByRef(9L, "not-a-hash")).isEmpty();
    }

    @Test
    void completeUnderstanding_serializesEntitiesAndClampsSummary() {
        AiChatImageEntity image = new AiChatImageEntity();
        image.setId(7L);
        when(imageMapper.selectById(7L)).thenReturn(image);
        when(imageMapper.updateById(image)).thenReturn(1);

        boolean updated = service.completeUnderstanding(7L, "发票金额", "x".repeat(301),
                List.of("合同A"));

        assertThat(updated).isTrue();
        assertThat(image.getOcrText()).isEqualTo("发票金额");
        assertThat(image.getImageSummary()).hasSize(300);
        assertThat(image.getKeyEntities()).isEqualTo("[\"合同A\"]");
    }

    @Test
    void localStorage_rejectsPathTraversal() {
        assertThatThrownBy(() -> storage.read("../outside.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
