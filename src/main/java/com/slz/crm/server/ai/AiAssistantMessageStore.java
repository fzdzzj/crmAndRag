package com.slz.crm.server.ai;

import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.mapper.AiMessageMapper;
import com.slz.crm.server.service.AiMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 助手消息占位与终态更新。
 *
 * <p>start 事件必须回传真实 assistantMessageId，因此先生成占位消息； 生成收口后再更新内容。完全无内容时删除占位，避免空壳污染历史。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAssistantMessageStore {

  private final AiMessageService aiMessageService;
  private final AiMessageMapper aiMessageMapper;

  /**
   * 创建助手占位消息。
   *
   * @param sessionId 所属会话
   * @return 已入库的占位消息，ID 供 start 事件回传
   */
  public AiMessageEntity createPlaceholder(Long sessionId) {
    return aiMessageService.saveMessage(
        sessionId, "assistant", "text", "", "{\"pending\":true}", 0);
  }

  /**
   * 更新助手消息终态。
   *
   * @param messageId 占位消息 ID
   * @param content 已剥离思考块后的正文
   * @param payload 结构化 JSON
   * @param tokenCount 模型 usage token；空/负数由 save 侧归零
   * @return true 表示更新成功
   */
  public boolean complete(Long messageId, String content, String payload, Integer tokenCount) {
    if (messageId == null) {
      return false;
    }
    AiMessageEntity message = aiMessageMapper.selectById(messageId);
    if (message == null) {
      log.warn("AI assistant placeholder missing, messageId={}", messageId);
      return false;
    }
    message.setContent(content == null ? "" : content);
    message.setPayload(payload);
    message.setTokenCount(tokenCount == null || tokenCount < 0 ? 0 : tokenCount);
    return aiMessageMapper.updateById(message) > 0;
  }

  /**
   * 删除完全无内容的占位消息，避免失败后留下空壳。
   *
   * @param messageId 占位消息 ID
   */
  public void deleteIfEmpty(Long messageId) {
    if (messageId == null) {
      return;
    }
    AiMessageEntity message = aiMessageMapper.selectById(messageId);
    if (message == null) {
      return;
    }
    if (message.getContent() == null || message.getContent().isBlank()) {
      aiMessageMapper.deleteById(messageId);
    }
  }
}
