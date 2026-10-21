package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.retrieval.SourceReferenceFactory;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * add-kb-image-slice-retrieval 任务 4.1：来源引用映射单测（IMAGE 取值 + 文档来源零回归）。
 *
 * <p>契约面断言：仍只填既有十字段；{@code sourceType=IMAGE}、{@code documentId}=资产 id、{@code filename}=描述摘要、 {@code
 * chunkId}=IMAGE:{资产 id}。
 */
@DisplayName("来源引用映射（任务组 2）")
class SourceReferenceFactoryTest {

  private static final String ASSET_ID = "90210";

  private static final String DESCRIPTION =
      "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长，" + "超时工单进入升级队列并通知值班经理。";

  @Test
  @DisplayName("图片切片命中：sourceType=IMAGE + documentId=资产 id + filename=描述摘要 + chunkId=IMAGE:{id}")
  void imageHit_mapsToImageSourceReference() {
    VectorSearchHit hit = imageHit(DESCRIPTION, "CHUNK-IGNORED");

    SourceReference reference = SourceReferenceFactory.from(hit, 0.87);

    assertThat(reference.sourceType()).isEqualTo("IMAGE");
    assertThat(reference.route()).isEqualTo("hybrid");
    assertThat(reference.documentId()).isEqualTo(ASSET_ID);
    assertThat(reference.chunkId()).isEqualTo("IMAGE:" + ASSET_ID);
    assertThat(reference.filename()).isEqualTo(DESCRIPTION.substring(0, 60) + "…");
    assertThat(reference.filename()).hasSize(61);
    assertThat(reference.chunkIndex()).isEqualTo(3);
    assertThat(reference.pageNo()).isEqualTo(2);
    assertThat(reference.rowIndex()).isNull();
    assertThat(reference.excerpt()).isEqualTo(DESCRIPTION);
    assertThat(reference.relevanceScore()).isEqualTo(0.87);
  }

  @Test
  @DisplayName("短描述不截断：filename 即原描述；换行/多空格压成单空格")
  void shortDescription_isKeptVerbatim() {
    VectorSearchHit hit = imageHit("机房\n  拓扑图：核心交换机双上行", "a.pdf");

    SourceReference reference = SourceReferenceFactory.from(hit, 0.5);

    assertThat(reference.filename()).isEqualTo("机房 拓扑图：核心交换机双上行");
    assertThat(reference.sourceType()).isEqualTo("IMAGE");
  }

  @Test
  @DisplayName("文档类来源零回归：fileType 空时回落文件名后缀，再回落 txt")
  void documentHit_keepsLegacyMapping() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("filename", "policy.pdf");
    metadata.put("fileType", "pdf");
    metadata.put("chunkIndex", 7);
    metadata.put("pageNo", 4L);
    metadata.put("rowIndex", 0L);

    SourceReference reference =
        SourceReferenceFactory.from(
            new VectorSearchHit("501", "doc-1", 0.66, "正文", metadata), 0.66);

    assertThat(reference.sourceType()).isEqualTo("pdf");
    assertThat(reference.route()).isEqualTo("hybrid");
    assertThat(reference.documentId()).isEqualTo("doc-1");
    assertThat(reference.chunkId()).isEqualTo("501");
    assertThat(reference.filename()).isEqualTo("policy.pdf");
    assertThat(reference.chunkIndex()).isEqualTo(7);
    assertThat(reference.pageNo()).isEqualTo(4);
    assertThat(reference.rowIndex()).isNull();
  }

  @Test
  @DisplayName("文档类来源：无 fileType 无后缀回落 txt（升级前行为逐字节一致）")
  void documentHitWithoutSuffix_fallsBackToTxt() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("filename", "manual");

    SourceReference reference =
        SourceReferenceFactory.from(new VectorSearchHit("502", "doc-2", 0.4, "正文", metadata), 0.4);

    assertThat(reference.sourceType()).isEqualTo("txt");
  }

  @Test
  @DisplayName("空描述摘要回落固定文案，不产生空 filename")
  void blankDescription_fallsBackToPlaceholder() {
    SourceReference reference = SourceReferenceFactory.from(imageHit("   ", "a.pdf"), 0.3);

    assertThat(reference.filename()).isEqualTo("图片切片");
    assertThat(reference.excerpt()).isEqualTo("   ");
  }

  private static VectorSearchHit imageHit(String text, String chunkId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", "5");
    metadata.put("filename", "架构图.pdf");
    metadata.put("fileType", "pdf");
    metadata.put(ImageSliceMetadata.KEY_SOURCE_TYPE, ImageSliceMetadata.SOURCE_TYPE_IMAGE);
    metadata.put(ImageSliceMetadata.KEY_IMAGE_ASSET_ID, ASSET_ID);
    metadata.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, "memory/x/image-1-0.png");
    metadata.put("chunkIndex", 3);
    metadata.put("pageNo", 2L);
    return new VectorSearchHit(chunkId, "doc-image-1", 0.87, text, metadata);
  }
}
