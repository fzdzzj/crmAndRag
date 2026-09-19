package com.slz.crm.knowledge.retrieval;

/**
 * 全文检索结果行（complete-hybrid-retrieval-and-rerank 任务 1.3）： {@code
 * DocumentVectorChunkMapper#fulltextSearch} 的投影——切片主键 + 元数据 + MATCH...AGAINST 相关度。 knowledgeBaseId
 * 来自与 uploaded_file 的 JOIN（授权过滤的执行点），切片表自身不存知识库归属。
 */
public class SparseChunkRow {
  /** 切片主键（即引用协议中的 chunkId）。 */
  private Long chunkId;

  /** 文档业务键。 */
  private String documentId;

  /** 切片序号。 */
  private Integer chunkIndex;

  /** 切片原文。 */
  private String chunkText;

  /** 来源文件名。 */
  private String filename;

  /** 类目标签。 */
  private String category;

  /** 页码（1 起，可空）。 */
  private Integer pageNo;

  /** Excel 行号（1 起，可空）。 */
  private Integer rowIndex;

  /** 所属知识库 ID 字符串（JOIN uploaded_file.knowledge_base 所得）。 */
  private String knowledgeBaseId;

  /** MATCH...AGAINST 相关度（无上界，仅作路内排序）。 */
  private Double score;

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long chunkId) {
    this.chunkId = chunkId;
  }

  public String getDocumentId() {
    return documentId;
  }

  public void setDocumentId(String documentId) {
    this.documentId = documentId;
  }

  public Integer getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(Integer chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public String getChunkText() {
    return chunkText;
  }

  public void setChunkText(String chunkText) {
    this.chunkText = chunkText;
  }

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public Integer getPageNo() {
    return pageNo;
  }

  public void setPageNo(Integer pageNo) {
    this.pageNo = pageNo;
  }

  public Integer getRowIndex() {
    return rowIndex;
  }

  public void setRowIndex(Integer rowIndex) {
    this.rowIndex = rowIndex;
  }

  public String getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public void setKnowledgeBaseId(String knowledgeBaseId) {
    this.knowledgeBaseId = knowledgeBaseId;
  }

  public Double getScore() {
    return score;
  }

  public void setScore(Double score) {
    this.score = score;
  }
}
