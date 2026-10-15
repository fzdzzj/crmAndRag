package com.slz.crm.platform.contract;

/**
 * 知识库来源引用（冻结契约，Lane B 填充，Lane C 下发，档 B 页级高亮依赖）。
 *
 * <p>页/段锚点（chunkIndex/pageNo/chunkId）与高亮协议背景见 D15；前端用 payload.citations 编号集匹配 答案内联 {@code [n]}，按
 * pageNo（PDF）/ rowIndex（Excel）定位。
 *
 * @param sourceType 来源类型：{@code pdf|docx|txt|md|excel|image|manual} 等，小写；业务实体来源为 {@code
 *     CUSTOMER}（add-customer-semantic-retrieval 受控解冻 2026-10-13：documentId=客户主键、
 *     filename=company_name）与 {@code BUSINESS_ACTIVITY}（add-business-activity-semantic-retrieval
 *     受控解冻 2026-10-14：documentId=活动主键、filename=活动标题），均复用现有字段零结构改动；spec 增量见
 *     openspec/changes/add-customer-semantic-retrieval/specs/knowledge-rag/spec-delta.md 与
 *     openspec/changes/add-business-activity-semantic-retrieval/specs/knowledge-rag/spec-delta.md）
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
