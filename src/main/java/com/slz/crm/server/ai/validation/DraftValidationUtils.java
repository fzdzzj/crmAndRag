package com.slz.crm.server.ai.validation;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * 草稿校验工具类：从 DTO 字段的 @AskQuestion 注解读取追问文案
 */
@Slf4j
public final class DraftValidationUtils {

    private DraftValidationUtils() {}

    /**
     * 从 DTO 字段的 @AskQuestion 注解读取追问文案（缺失时回退到校验消息）
     */
    public static String resolveAskQuestion(Class<?> dtoClass, String fieldName, String fallbackMessage) {
        try {
            Field field = dtoClass.getDeclaredField(fieldName);
            AskQuestion askQuestion = field.getAnnotation(AskQuestion.class);
            if (askQuestion != null) {
                return askQuestion.value();
            }
        } catch (NoSuchFieldException e) {
            log.warn("草稿字段不存在: {}.{}", dtoClass.getSimpleName(), fieldName);
        }
        return fallbackMessage;
    }
}
