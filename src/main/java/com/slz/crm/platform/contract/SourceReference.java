package com.slz.crm.platform.contract;

/**
 * 知识库来源引用（冻结契约，Lane B 填充，Lane C 下发，档 B 页级高亮依赖）。
 *
 * <p>为什么必须带 {@code chunkIndex/pageNo/chunkId}： RAG 原实现只回传
 * filename/documentId/excerpt/score，页码只落在日志里， PDF 又把整页 merge 后再切块，导致前端无法定位到页/段（D15 明确要求补齐锚点）。
 *
 * <p>高亮协议：前端收到 {@code references} 事件后，用 {@code payload.citations} 里的编号集 匹配答案内联 {@code [n]}，再按
 * {@code pageNo}（PDF）/ {@code rowIndex}（Excel）定位。
 *
 * @param sourceType 来源类型：{@code pdf|docx|txt|md|excel|image|manual} 等，小写
 * @param route 检索路由：{@code vector|keyword|hybrid|ocr}（便于评测归因）
 * @param filename 展示用文件名（含扩展名，不含路径）
 * @param documentId 文档主键
 * @param chunkId 切片主键
 * @param chunkIndex 切片序号（0 起，按页分块后即“第几块”）
 * @param pageNo PDF/长文页码（1 起；非分页文档为 null）
 * @param rowIndex Excel 行号（1 起；非表格为 null）
 * @param excerpt 命中片段文本（原样返回，前端做段内匹配高亮）
 * @param relevanceScore 相似度得分（0~1，越大越相关）
 */
public record SourceReference(
    String sourceType,
    String route,
    String filename,
    String documentId,
    String chunkId,
    Integer chunkIndex,
    Integer pageNo,
    Integer rowIndex,
    String excerpt,
    Double relevanceScore) {}
