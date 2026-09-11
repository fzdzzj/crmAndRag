package com.slz.crm.platform.config.controller;

import lombok.Data;

/**
 * 更新配置项请求体。
 */
@Data
public class ConfigUpdateRequest {

    /** 配置键（必须已注册，如 rag.retrieval.topK） */
    private String key;

    /** 配置值原始输入（写前过类型/范围/枚举校验护栏） */
    private String value;

    /** 操作备注（可为空） */
    private String remark;
}
