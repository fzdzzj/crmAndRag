package com.slz.crm.pojo.vo;

import lombok.Data;

/**
 * 知识库文档切片（chunk）视图。
 *
 * <p>add-source-citation-and-dashboard 任务 1.3：普通用户（挂 803 AI_CHAT_SESSION）读取知识库文档来源原文 的返回结构，含档 B
 * 页级高亮依赖的定位字段（chunkIndex/pageNo/rowIndex），前端据此在文档查看抽屉滚动定位并高亮 excerpt。
 */
@Data
public class KnowledgeChunkVO {
  /** 切片主键（即引用协议中的 chunkId）。 */
  private Long chunkId;

  /** 文档业务键。 */
  private String documentId;

  /** 切片原文。 */
  private String chunkText;

  /** 来源文件名。 */
  private String filename;

  /** 稿件切片序号（前端滚动定位锚）。 */
  private Integer chunkIndex;

  /** 页码（1 起，可空；档 B 页级高亮依赖）。 */
  private Integer pageNo;

  /** Excel 行号（1 起，可空）。 */
  private Integer rowIndex;
}
