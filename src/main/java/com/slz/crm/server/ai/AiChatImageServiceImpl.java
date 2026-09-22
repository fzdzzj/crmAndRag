package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.server.mapper.AiChatImageMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 聊天图片服务默认实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatImageServiceImpl implements AiChatImageService {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int SUMMARY_MAX_CHARS = 300;

  private final AiChatImageMapper imageMapper;
  private final AiChatImageStorage imageStorage;

  @Override
  public AiChatImageEntity save(Long sessionId, Long userId, String contentType, byte[] content) {
    if (sessionId == null || userId == null || content == null || content.length == 0) {
      throw new IllegalArgumentException("聊天图片保存参数不完整");
    }
    String imageHash = LocalAiChatImageStorage.sha256Hex(content);
    AiChatImageEntity result = findByHash(sessionId, imageHash);
    if (result == null) {
      AiChatImageEntity image = new AiChatImageEntity();
      image.setSessionId(sessionId);
      image.setUserId(userId);
      image.setImageHash(imageHash);
      image.setStorageBackend(imageStorage.backend());
      image.setStorageKey(imageStorage.store(sessionId, imageHash, content, contentType));
      image.setCreateTime(LocalDateTime.now());
      image.setUpdateTime(image.getCreateTime());
      image.setIsDeleted(false);
      try {
        imageMapper.insert(image);
        result = image;
      } catch (DuplicateKeyException exception) {
        // 并发上传同一张图时回读已有 L1 记录，避免重复 vision 调用。
        log.info("聊天图片并发保存冲突，回读已有记录: sessionId={}, hash={}", sessionId, imageHash);
        AiChatImageEntity winner = findByHash(sessionId, imageHash);
        if (winner != null) {
          result = winner;
        } else {
          throw exception;
        }
      }
    }
    return result;
  }

  @Override
  public Optional<AiChatImageEntity> findByRef(Long sessionId, String imageRef) {
    Optional<AiChatImageEntity> result = Optional.empty();
    if (sessionId != null && imageRef != null && !imageRef.isBlank()) {
      String normalized = imageRef.trim();
      AiChatImageEntity image;
      if (normalized.chars().allMatch(Character::isDigit)) {
        image = imageMapper.selectById(Long.parseLong(normalized));
        if (image != null && !sessionId.equals(image.getSessionId())) {
          image = null;
        }
      } else if (LocalAiChatImageStorage.isHex64(normalized)) {
        image = findByHash(sessionId, normalized.toLowerCase());
      } else {
        image = null;
      }
      result = Optional.ofNullable(image);
    }
    return result;
  }

  @Override
  public boolean completeUnderstanding(
      Long id, String ocrText, String imageSummary, List<String> keyEntities) {
    boolean result = false;
    if (id != null) {
      AiChatImageEntity image = imageMapper.selectById(id);
      if (image != null) {
        String summary = imageSummary == null ? "" : imageSummary.trim();
        image.setOcrText(ocrText == null ? "" : ocrText.trim());
        image.setImageSummary(
            summary.length() > SUMMARY_MAX_CHARS
                ? summary.substring(0, SUMMARY_MAX_CHARS)
                : summary);
        image.setKeyEntities(serializeEntities(keyEntities));
        image.setUpdateTime(LocalDateTime.now());
        result = imageMapper.updateById(image) > 0;
      }
    }
    return result;
  }

  @Override
  public byte[] readBytes(AiChatImageEntity image) {
    if (image == null || image.getStorageKey() == null || image.getStorageKey().isBlank()) {
      throw new IllegalArgumentException("图片存储键缺失");
    }
    return imageStorage.read(image.getStorageKey());
  }

  private AiChatImageEntity findByHash(Long sessionId, String imageHash) {
    return imageMapper.selectOne(
        new LambdaQueryWrapper<AiChatImageEntity>()
            .eq(AiChatImageEntity::getSessionId, sessionId)
            .eq(AiChatImageEntity::getImageHash, imageHash));
  }

  private String serializeEntities(List<String> keyEntities) {
    String result;
    try {
      result = OBJECT_MAPPER.writeValueAsString(keyEntities == null ? List.of() : keyEntities);
    } catch (Exception exception) {
      log.warn("聊天图片关键实体序列化失败，按空数组保存", exception);
      result = "[]";
    }
    return result;
  }
}
