package com.slz.crm.platform.contract;

import java.util.List;

/**
 * 平台级向量库抽象（跨 lane 冻结契约，D7/D9）。
 *
 * <p>为什么不用 Spring AI 的 {@code VectorStore} 直接当契约： D9 要求“真 Qdrant + 内存回退”两条实现并存，且我们还需要把 {@code
 * documentId/chunkIndex/pageNo} 等元数据原样带出（档 B 页级高亮依赖）， Spring AI 的泛型 filter/Document
 * 语义对“页码/行号”这类业务锚点表达力不足。 因此这里定义最小平台语义，由 Lane B 提供两种实现：
 *
 * <ul>
 *   <li>{@code QdrantVectorStore}（生产默认，持久化 + ANN）；
 *   <li>{@code InMemoryVectorStore}（dev/test 回退，纯余弦相似度，严禁生产）。
 * </ul>
 *
 * <p>线程安全：实现 MUST 支持并发 upsert/search；内存实现需要保证读多写少场景下不阻塞检索。
 *
 * <p>失败边界：Qdrant 不可达时实现 MAY 抛异常，由上层决定降级（不在这里静默吞错）。
 */
public interface CrmVectorStore {

  /**
   * 写入或覆盖一条向量。
   *
   * @param record 向量记录（id 唯一；重复 id 视为覆盖）
   */
  void upsert(VectorRecord record);

  /**
   * 批量写入（文档入库的常规路径，实现应尽量复用连接）。
   *
   * @param records 向量记录集合；空集合为合法调用（直接返回）
   */
  void upsertAll(List<VectorRecord> records);

  /**
   * 相似度检索。
   *
   * @param request 查询向量 + topK + 元数据过滤
   * @return 按 score 降序的命中列表；无命中返回空列表（不返回 null）
   */
  List<VectorSearchHit> search(VectorSearchRequest request);

  /**
   * 按文档删除全部切片向量（文档重传/删除时用）。
   *
   * @param documentId 文档主键
   */
  void deleteByDocumentId(String documentId);
}
