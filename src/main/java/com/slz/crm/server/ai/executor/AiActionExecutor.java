package com.slz.crm.server.ai.executor;

/** 草稿执行策略接口：按 actionType 分发，确认时执行真实写操作， 返回执行结果与新创建实体的引用（供确认卡片渲染跳转标签） */
public interface AiActionExecutor {
  String actionType();

  AiExecutionResult execute(String payloadJson);
}
