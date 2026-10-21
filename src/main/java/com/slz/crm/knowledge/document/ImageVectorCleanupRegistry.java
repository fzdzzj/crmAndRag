package com.slz.crm.knowledge.document;

import com.slz.crm.platform.contract.CrmVectorStore;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片视觉向量清除钩子（multimodal-shared-space 任务 3.2）。
 *
 * <p>为什么是静态钩子而不是方法入参：删除双清链的另一端是 {@code server/service/KnowledgeAdminService#deleteFile}
 * （本卡写集之外），它只调用 {@link ImageAssetCleanup#deleteAssets} 的既有静态签名；要让文档删除也清 image
 * 集合，只能让清理工具类自己发现"已装配的图片向量库"。钩子由 {@code ImageVectorStoreConfiguration} 在装配时绑定一次，
 * 未绑定（单测/自定义装配/未启用视觉路）= null，删除行为与升级前逐字节一致。
 *
 * <p>失败语义与图片资产清理一致：单个依赖失败只 WARN，绝不打断以行为权威的物理双清。
 */
public final class ImageVectorCleanupRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ImageVectorCleanupRegistry.class);

  private static final AtomicReference<CrmVectorStore> IMAGE_STORE = new AtomicReference<>();

  private ImageVectorCleanupRegistry() {}

  /**
   * 绑定已装配的图片向量库（幂等；重复绑定以后者为准，便于测试替换）。
   *
   * @param imageVectorStore 图片视觉向量库（null = 解绑）
   */
  public static void bind(CrmVectorStore imageVectorStore) {
    IMAGE_STORE.set(imageVectorStore);
  }

  /** 解绑（测试清理用；生产在进程生命周期内只绑定一次）。 */
  public static void unbind() {
    IMAGE_STORE.set(null);
  }

  /**
   * @return 当前是否已装配图片向量库。
   */
  public static boolean assembled() {
    return IMAGE_STORE.get() != null;
  }

  /**
   * @return 已装配的图片向量库（未装配 = null；写路径 {@code ImageVectorFlushSupport} 用）。
   */
  static CrmVectorStore store() {
    return IMAGE_STORE.get();
  }

  /**
   * 清某文档在 image 集合的全部视觉向量（按 documentId，与文本集合同一清理口径）。
   *
   * @param documentId 文档业务键（空 = 零调用）
   * @return 是否真的发生了清理调用（未装配 = false）
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 外部库删除多源：失败只告警，不挡文本侧权威清理
  public static boolean deleteByDocumentId(String documentId) {
    CrmVectorStore store = IMAGE_STORE.get();
    boolean invoked = store != null && documentId != null && !documentId.isBlank();
    if (invoked) {
      try {
        store.deleteByDocumentId(documentId);
      } catch (Exception exception) {
        LOG.warn("图片视觉向量清理失败（文本侧双清继续）: documentId={}", documentId, exception);
        invoked = false;
      }
    }
    return invoked;
  }
}
