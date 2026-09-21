package com.slz.crm.platform.contract;

import java.util.List;

/**
 * 助手流式对话请求契约（冻结契约，Lane C 拥有 SSE 实现，Lane B 提供检索能力）。
 *
 * <p>路径约定：{@code POST /ai/chat/stream}。字段语义与 assistant-decision-tree.md 对齐：
 *
 * <ul>
 *   <li>{@code sessionId}：首次可传 null 由后端生成；同一 sessionId 的流式请求被 AiStreamRegistry 串行化（D14 记忆并发约束）；
 *   <li>{@code useKnowledgeBase}：知识库开关（D12：手动开关，不由 LLM 自动决定）；
 *   <li>{@code imageRef}：<b>助手会话内聊天图片</b>的引用（D13：显式指明、不回退最近一张图； 非 CRM 业务附件、非 Lane B 的 {@code
 *       uploaded_file}，C 域按 hash 持久化 L1 理解）。
 * </ul>
 *
 * @param sessionId 会话 id，允许为 null（后端生成后经 {@code start} 事件回传）
 * @param message 用户输入，不能为空串
 * @param useKnowledgeBase 是否启用知识库检索
 * @param thinking 是否需要思考流
 * @param imageRef 聊天图片引用（存储键或已持久化 L1 理解的 hash），允许为 null
 * @param attachments 附件引用集合（业务文件 id / 知识库文档 id），允许为 null
 */
public record AssistantChatRequest(
    String sessionId,
    String message,
    Boolean useKnowledgeBase,
    Boolean thinking,
    String imageRef,
    List<String> attachments) {

  /**
   * 归一化构造：把 Boolean 统一成默认语义，避免上层到处判 null。
   *
   * @throws IllegalArgumentException message 为空时抛出
   */
  public AssistantChatRequest {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message 不能为空");
    }
    useKnowledgeBase = useKnowledgeBase != null && useKnowledgeBase;
    thinking = thinking != null && thinking;
    if (attachments == null) {
      attachments = List.of();
    } else {
      attachments = List.copyOf(attachments);
    }
  }
}
