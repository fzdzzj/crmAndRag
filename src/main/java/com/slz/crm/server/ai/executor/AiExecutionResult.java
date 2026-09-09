package com.slz.crm.server.ai.executor;

import com.slz.crm.server.ai.AiReferenceCollector;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 草稿执行结果：执行结果 JSON + 新创建实体的引用（供确认卡片渲染跳转标签）
 */
@Data
@AllArgsConstructor
public class AiExecutionResult {

    /** 执行结果 JSON（与原返回格式一致） */
    private String result;

    /** 新创建实体的引用（拿不到新实体信息时为空列表，不影响确认结果） */
    private List<AiReferenceCollector.Reference> references;

    public static AiExecutionResult of(String result) {
        return new AiExecutionResult(result, List.of());
    }

    public static AiExecutionResult of(String result, List<AiReferenceCollector.Reference> references) {
        return new AiExecutionResult(result, references == null ? List.of() : references);
    }
}
