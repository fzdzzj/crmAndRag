package com.slz.crm.server.ai.port;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * B 实现未接线前的空结果 mock。
 *
 * <p>返回空结果而不是伪造来源；主答层负责按 D16 继续正常生成。
 * B 实现合入时关闭 {@code crm.ai.knowledge-retrieval.mock-enabled} 即可停用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "crm.ai.knowledge-retrieval", name = "mock-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockKnowledgeRetrievalPort implements KnowledgeRetrievalPort {

    @Override
    public RetrievalResult retrieve(RetrievalQuery query) {
        return RetrievalResult.empty();
    }
}
