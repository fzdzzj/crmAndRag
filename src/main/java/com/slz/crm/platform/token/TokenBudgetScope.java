package com.slz.crm.platform.token;

/**
 * Token 预算维度。
 */
public enum TokenBudgetScope {

    /** 按用户累计。 */
    USER,
    /** 按会话或业务批次累计。 */
    SESSION,
    /** 按知识库累计。 */
    KNOWLEDGE_BASE,
    /** 全局累计。 */
    GLOBAL
}
