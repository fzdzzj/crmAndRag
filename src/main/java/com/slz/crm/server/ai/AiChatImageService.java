package com.slz.crm.server.ai;

import com.slz.crm.pojo.entity.AiChatImageEntity;
import java.util.List;
import java.util.Optional;

/** 聊天图片引用与 L1 理解持久化服务。 */
public interface AiChatImageService {

  /** 保存新图或按会话内 hash 复用已有记录。 */
  AiChatImageEntity save(Long sessionId, Long userId, String contentType, byte[] content);

  /** imageRef 支持本表 ID 或 64 位 SHA-256；只允许引用同会话图片。 */
  Optional<AiChatImageEntity> findByRef(Long sessionId, String imageRef);

  /** 写入 OCR/摘要/实体；同一张图换问题时只重算问题聚焦，不重跑 L1。 */
  boolean completeUnderstanding(
      Long id, String ocrText, String imageSummary, List<String> keyEntities);

  byte[] readBytes(AiChatImageEntity image);
}
