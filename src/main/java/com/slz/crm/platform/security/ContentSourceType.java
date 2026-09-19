package com.slz.crm.platform.security;

/** 内容安全检查来源。 */
public enum ContentSourceType {

  /** 用户在对话中输入的内容。 */
  USER_INPUT,
  /** 知识库入库文档。 */
  DOCUMENT,
  /** 检索后送入模型的片段。 */
  RETRIEVAL_CHUNK
}
