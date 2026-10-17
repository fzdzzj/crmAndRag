package com.slz.crm.server.ai;

import com.slz.crm.platform.security.ContentSecurityResult;
import com.slz.crm.platform.security.ContentSecurityService;
import com.slz.crm.platform.security.ContentSourceType;

/**
 * 聊天内容安全接线支持（wire-built-unfinished-infra 任务 1.2，自 AiChatPromptService 拆出以守 PMD NcssCount）。
 *
 * <p>对进入模型上下文的用户内容执行 evaluate（落审计事件）+ wrap（按服务已实现语义处置：BLOCK 抛 {@link
 * com.slz.crm.platform.security.ContentRiskBlockedException}、DEGRADE 降级包装、ALLOW 原文）。
 */
final class AiChatContentSecuritySupport {

  private AiChatContentSecuritySupport() {}

  /**
   * 用户输入内容安全闸：服务缺席（手工装配单测）时空跳过原样放行。
   *
   * @param service 内容安全服务（生产 bean 恒在）
   * @param sessionId 会话 ID，作为审计事件 sourceId
   * @param content 即将进入模型上下文的改写问句
   * @return 原文或降级包装文本
   */
  static String secureUserContent(ContentSecurityService service, Long sessionId, String content) {
    String result = content;
    if (service != null) {
      ContentSecurityResult security =
          service.evaluate(ContentSourceType.USER_INPUT, String.valueOf(sessionId), content);
      result = service.wrapUntrustedContent(content, security);
    }
    return result;
  }
}
