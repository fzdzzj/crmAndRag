package com.slz.crm.server.ai.validation;

/**
 * 草稿校验策略接口：按 actionType 分发，每种写操作实现自己的校验规则
 */
public interface AiActionValidator {
    String actionType();
    AiValidationResult validate(String payloadJson);
}