package com.slz.crm.server.ai;

import java.util.List;
import java.util.Objects;

/**
 * Self-RAG 反思产物（add-self-rag-reflection 任务 1.1）。
 *
 * @param answer 答案文本（无据断言软降级时可能追加尾注）
 * @param citations 校验过滤后的 1-based 引用编号列表（保持顺序、去重）
 * @param fallback 是否发生了降级/回退
 */
public record SelfRagResult(String answer, List<Integer> citations, boolean fallback) {

  public SelfRagResult(String answer, List<Integer> citations) {
    this(answer, citations, false);
  }

  public SelfRagResult {
    answer = Objects.requireNonNullElse(answer, "");
    citations = citations == null ? List.of() : List.copyOf(citations);
  }
}
