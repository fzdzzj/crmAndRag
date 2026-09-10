package com.slz.crm.knowledge.embedding;

import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderProperties;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Service;

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
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("嵌入文本不能为空");
        }
        var request = new EmbeddingRequest(
                java.util.List.of(text),
                EmbeddingOptionsBuilder.builder().withModel(properties.getEmbeddingModel()).build());
        float[] vector = modelProvider.embed(request).vector();
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("EmbeddingProvider 返回空向量");
        }
        return vector;
    }
}
