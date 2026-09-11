package com.slz.crm.knowledge.storage;

/**
 * 文件存储统一异常。
 */
public class StorageException extends RuntimeException {
    /** 使用业务信息构造异常。 */
    public StorageException(String message) {
        super(message);
    }

    /** 保留底层原因，便于排查 MinIO/网络错误。 */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
