package com.slz.crm.platform.contract;

/**
 * Token 计量类型（冻结契约，B/C 产数，D 聚合）。
 *
 * <p>枚举完整性说明：意图分类与摘要也要计量（D14 补盲点）， 因此必须有 {@link #INTENT} 与 {@link #SUMMARY}，不能只有 chat/embedding。
 */
public enum TokenUsageType {

  /** 普通对话生成（含工具调用循环中的各轮） */
  CHAT("chat"),
  /** 文本向量化（入库 + 检索双向都要记） */
  EMBEDDING("embedding"),
  /** 图片/扫描件 OCR 文本抽取 */
  OCR("ocr"),
  /** 视觉理解（多模态对话） */
  VISION("vision"),
  /** 会话摘要生成（记忆持久化旁路） */
  SUMMARY("summary"),
  /** 意图/类目识别（记忆持久化旁路） */
  INTENT("intent");

  private final String wireName;

  TokenUsageType(String wireName) {
    this.wireName = wireName;
  }

  /**
   * @return 落库/上报用的稳定标识（小写）
   */
  public String wireName() {
    return wireName;
  }
}
