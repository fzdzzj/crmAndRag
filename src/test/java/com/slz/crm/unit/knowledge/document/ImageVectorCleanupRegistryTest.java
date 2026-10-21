package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.knowledge.document.ImageVectorCleanupRegistry;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * multimodal-shared-space 任务 5.1：image 集合清除钩子单测。
 *
 * <p>覆盖：未装配时零调用（默认行为零变化）/ 装配后按 documentId 真清（与文本同口径）/ 依赖异常只降级不抛。
 */
@DisplayName("图片视觉向量清除钩子（任务 3.2）")
class ImageVectorCleanupRegistryTest {

  /**
   * 每个用例前解绑：钩子是进程级静态绑定，同一 JVM 里跑过的 Spring 上下文（装配过 ImageVectorStore bean）可能已绑过，
   * 用例必须显式回到"未装配"基线再断言，不能依赖执行顺序。
   */
  @BeforeEach
  void resetBinding() {
    ImageVectorCleanupRegistry.unbind();
  }

  @AfterEach
  void tearDown() {
    ImageVectorCleanupRegistry.unbind();
  }

  @Test
  @DisplayName("未装配：不调用、不抛，返回 false（升级前行为逐字节一致）")
  void unassembled_isNoOp() {
    assertThat(ImageVectorCleanupRegistry.assembled()).isFalse();
    assertThat(ImageVectorCleanupRegistry.deleteByDocumentId("doc-1")).isFalse();
  }

  @Test
  @DisplayName("已装配：按 documentId 清 image 集合，其他文档的视觉向量保留")
  void assembled_deletesOnlyTargetDocument() {
    ImageVectorStore store = new ImageVectorStore(new InMemoryVectorStore());
    ImageVectorCleanupRegistry.bind(store);
    store.upsert(record("p1", "doc-1"));
    store.upsert(record("p2", "doc-2"));

    assertThat(ImageVectorCleanupRegistry.assembled()).isTrue();
    assertThat(ImageVectorCleanupRegistry.deleteByDocumentId("doc-1")).isTrue();

    assertThat(store.search(anyQuery()))
        .extracting(hit -> hit.documentId())
        .containsExactly("doc-2");
  }

  @Test
  @DisplayName("已装配但删除抛错：只告警降级为 false，不打断文本侧权威双清")
  void assembledFailure_degradesWithoutThrowing() {
    ImageVectorStore failing =
        new ImageVectorStore(new InMemoryVectorStore()) {
          @Override
          public void deleteByDocumentId(String documentId) {
            throw new IllegalStateException("image 集合不可达");
          }
        };
    ImageVectorCleanupRegistry.bind(failing);

    assertThat(ImageVectorCleanupRegistry.deleteByDocumentId("doc-1")).isFalse();
  }

  private static VectorRecord record(String id, String documentId) {
    return new VectorRecord(
        id,
        documentId,
        "chunk-" + id,
        0,
        "描述",
        new float[] {1F, 0F},
        Map.of("knowledgeBaseId", "5"));
  }

  private static VectorSearchRequest anyQuery() {
    return new VectorSearchRequest(new float[] {1F, 0F}, 10, null, Map.of());
  }
}
