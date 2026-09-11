package com.slz.crm.knowledge.document;

import com.slz.crm.platform.contract.UserContext;

import java.io.InputStream;

/**
 * 文档入库命令。
 *
 * @param knowledgeBaseId 已授权知识库 ID
 * @param user            当前登录用户
 * @param filename        原始文件名
 * @param contentType     MIME 类型
 * @param fileSize        文件大小；未知可空
 * @param category        检索类目
 * @param batchTaskId     批量任务 ID；单文件可空
 * @param content         文件内容
 */
public record DocumentIngestionCommand(
        Long knowledgeBaseId,
        UserContext user,
        String filename,
        String contentType,
        Long fileSize,
        String category,
        String batchTaskId,
        InputStream content) {
}
