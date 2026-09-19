package com.slz.crm.server.ai;

import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/** 统一工具执行函数式接口（允许抛出受检异常，由 buildToolCallback 统一兜底） */
@FunctionalInterface
public interface AiToolExecutor {
  Object execute(Map<String, Object> args, ToolContext toolContext) throws Exception;
}
