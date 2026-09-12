package com.slz.crm.knowledge.embedding;

import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderProperties;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 嵌入服务；所有调用统一经过 ModelProvider。
 */
@Service
public class EmbeddingService {
    private final ModelProvider modelProvider;
    private final ModelProviderProperties properties;

    public EmbeddingService(ModelProvider modelProvider, ModelProviderProperties properties) {
        this.modelProvider = modelProvider;
        this.properties = properties;
    }

    /** 单文本向量化；Provider 返回的 usage 由调用链保留，本方法不吞模型错误。 */
    public float[] embed(String text) {
        return embedWithUsage(text).vector();
    }

    /**
     * 单文本向量化并保留 usage（提案5 任务 3.3：衍生问题嵌入计量需要回传 token 消耗，
     * 检索侧既有调用仍走 {@link #embed} 口径不变）。失败语义与 {@link #embed} 一致。
     */
    public ModelCallResult<float[]> embedWithUsage(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("嵌入文本不能为空");
        }
        EmbeddingRequest request = new EmbeddingRequest(
                List.of(text),
                EmbeddingOptionsBuilder.builder().withModel(properties.getEmbeddingModel()).build());
        ModelCallResult<float[]> result = modelProvider.embed(request);
        if (result == null || result.vector() == null || result.vector().length == 0) {
            throw new IllegalStateException("EmbeddingProvider 返回空向量");
        }
        return result;
    }
}
