package com.slz.crm.unit.ai;

import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.ai.port.MockKnowledgeRetrievalPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 知识库检索缝的输入复制与空 mock 降级验证。 */
class KnowledgeRetrievalPortTest {

    @Test
    void retrievalQuery_copiesScopeAndMockReturnsEmpty() {
        KnowledgeRetrievalPort port = new MockKnowledgeRetrievalPort();
        List<String> scope = new java.util.ArrayList<>(List.of("crm", "policy"));
        KnowledgeRetrievalPort.RetrievalQuery query = new KnowledgeRetrievalPort.RetrievalQuery(
                "客户A合同金额", 42L, scope, 5, null, "contract");
        scope.add("unexpected");

        KnowledgeRetrievalPort.RetrievalResult result = port.retrieve(query);

        assertThat(query.kbScope()).containsExactly("crm", "policy");
        assertThat(result.context()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(result.hitCount()).isZero();
    }
}
