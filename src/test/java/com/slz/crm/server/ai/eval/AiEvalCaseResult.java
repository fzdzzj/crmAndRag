package com.slz.crm.server.ai.eval;

import java.util.List;

/** 单条用例评分明细。 missing/unexpected 参数用扁平路径表示，例如 order.items[0].productName。 */
public record AiEvalCaseResult(
    String caseId,
    String expectedTool,
    String actualTool,
    boolean toolSelected,
    boolean paramsMatched,
    List<String> missingParams,
    List<String> mismatchedParams,
    List<String> unexpectedParams,
    String notes) {}
