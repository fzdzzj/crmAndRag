package com.slz.crm.platform.quota;

/** 请求配额维度。 */
public enum QuotaDimension {

  /** 按登录用户限流。 */
  USER,
  /** 按客户端 IP 限流，用于未登录入口或异常调用。 */
  IP,
  /** 按知识库限流，防止单库入库/检索抢占全局资源。 */
  KNOWLEDGE_BASE,
  /** 全局限流，作为最后一级资源保护。 */
  GLOBAL
}
