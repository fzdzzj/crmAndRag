package com.slz.crm.knowledge.document;

/**
 * 解析出的页级文本；Excel 一行视作一个页级锚点。
 *
 * @param pageNo 页码，1 起；Excel 固定为 1
 * @param rowIndex Excel 行号，1 起；非表格为 null
 * @param text 页文本
 */
public record DocumentPage(Integer pageNo, Integer rowIndex, String text) {}
