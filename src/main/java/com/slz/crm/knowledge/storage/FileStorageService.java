package com.slz.crm.knowledge.storage;

import java.io.InputStream;

/**
 * 原始文件存储抽象。
 *
 * <p>默认 MinIO；内存实现仅限本地或测试，生产 MUST 使用对象存储。</p>
 */
public interface FileStorageService {
    /** 保存文件并返回稳定 storageKey。 */
    String store(InputStream content, String filename, String contentType);

    /** 打开对象输入流；调用方负责关闭。 */
    InputStream open(String storageKey);

    /** 删除对象；对象不存在时视为已删除。 */
    void delete(String storageKey);

    /** 判断对象是否存在。 */
    boolean exists(String storageKey);
}
