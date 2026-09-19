package com.slz.crm.knowledge.document;

import java.util.List;

/**
 * 文档解析结果。
 *
 * @param fileType 小写文件类型
 * @param pages 页级文本
 */
public record ParsedDocument(String fileType, List<DocumentPage> pages) {}
