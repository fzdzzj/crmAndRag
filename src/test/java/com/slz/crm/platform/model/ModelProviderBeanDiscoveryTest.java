package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 验证 {@link ModelProviderImpl} 的 Spring Bean 注册和 {@code ObjectProvider<ModelProvider>} 可发现性。
 *
 * <p>为什么要这个测试：IT 手工构造不走 Spring 容器，无法证明 Bean 注册。
 * 消费方（Lane B/C/D）约定用 {@code ObjectProvider<ModelProvider>} 注入做缺 Bean 安全降级，
 * 本测试用最小上下文实打实验证注册链路。</p>
 */
class ModelProviderBeanDiscoveryTest {

    @Configuration
    static class TestConfig {
        @Bean
        ModelProviderProperties modelProviderProperties() {
            ModelProviderProperties props = new ModelProviderProperties();
            props.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
            props.setApiKey("test-key");
            return props;
        }

        @Bean
        ModelProviderImpl modelProviderImpl(ModelProviderProperties props) {
            return new ModelProviderImpl(
                    new ObjectProvider<org.springframework.ai.chat.model.ChatModel>() {
                        @Override
                        public org.springframework.ai.chat.model.ChatModel getObject() {
                            throw new IllegalStateException("no ChatModel in test");
                        }
                        @Override
                        public org.springframework.ai.chat.model.ChatModel getIfAvailable() {
                            return null;
                        }
                    },
                    new ObjectProvider<org.springframework.ai.embedding.EmbeddingModel>() {
                        @Override
                        public org.springframework.ai.embedding.EmbeddingModel getObject() {
                            throw new IllegalStateException("no EmbeddingModel in test");
                        }
                        @Override
                        public org.springframework.ai.embedding.EmbeddingModel getIfAvailable() {
                            return null;
                        }
                    },
                    props,
                    new org.springframework.mock.env.MockEnvironment());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void modelProviderBeanIsRegisteredAndDiscoverableByObjectProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ModelProviderImpl.class);
            assertThat(context).hasSingleBean(ModelProvider.class);
            ObjectProvider<ModelProvider> provider = context.getBeanProvider(ModelProvider.class);
            assertThat(provider.getIfAvailable()).isNotNull().isInstanceOf(ModelProviderImpl.class);
        });
    }

    @Test
    void modelProviderBeanIsNotRegisteredWithoutConfiguration() {
        new ApplicationContextRunner().run(context -> {
            assertThat(context).doesNotHaveBean(ModelProvider.class);
            ObjectProvider<ModelProvider> provider = context.getBeanProvider(ModelProvider.class);
            assertThat(provider.getIfAvailable()).isNull();
        });
    }
}
