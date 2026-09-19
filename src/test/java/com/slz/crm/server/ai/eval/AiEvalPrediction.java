package com.slz.crm.server.ai.eval;

import java.util.Map;

/** 单条用例的模型或回放预测结果。 */
public record AiEvalPrediction(String toolName, Map<String, Object> arguments, String notes) {}
