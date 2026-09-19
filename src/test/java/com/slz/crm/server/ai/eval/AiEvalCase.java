package com.slz.crm.server.ai.eval;

import java.util.List;
import java.util.Map;

/** AI 工具选择评测用例。 expectedParams 只约束业务关键参数，不约束模型可能补充的可选参数。 */
public record AiEvalCase(
    String id,
    String utterance,
    String expectedTool,
    Map<String, Object> expectedParams,
    List<String> tags) {}
