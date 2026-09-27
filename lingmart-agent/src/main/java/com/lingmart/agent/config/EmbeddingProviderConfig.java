package com.lingmart.agent.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 知识库嵌入模型 Bean（默认阿里云百炼 DashScope）。
 *
 * <p>为什么需要手写：对话模型默认 DeepSeek，而 DeepSeek 只提供 chat、<b>不提供 embedding 接口</b>；
 * 知识库向量化需要一个独立的嵌入厂商。本配置用同一份 openai starter 客户端另起一条指向
 * 百炼 OpenAI 兼容端点的 {@link EmbeddingModel}（读独立 Key {@code DASHSCOPE_API_KEY}），
 * 与 DeepSeek 对话互不干扰。主配置把 {@code spring.ai.model.embedding} 置为 {@code none}
 * 关闭了 openai 的 embedding 自动装配，因此这里成为唯一 EmbeddingModel。
 * 备用 Ollama profile 会置 {@code lingmart.agent.embedding.enabled=false} 让位给 Ollama 嵌入。</p>
 */
@Configuration
@ConditionalOnProperty(name = "lingmart.agent.embedding.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingProviderConfig {

    @Bean
    public EmbeddingModel dashScopeEmbeddingModel(
            @Value("${lingmart.agent.embedding.api-key:}") String apiKey,
            @Value("${lingmart.agent.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${lingmart.agent.embedding.model:text-embedding-v3}") String model,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        // ApiKey 为函数式接口：直接以 lambda 提供（不缓存、不打印）
        ApiKey key = () -> apiKey;
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .embeddingsPath("/embeddings");
        // 复用 Boot 装配的 RestClient.Builder（已带 spring.http.client.* 连接/读超时），使嵌入调用同样受
        // HTTP 层超时保护——知识检索是同步内嵌在单轮会话里的，若嵌入请求悬挂会整轮拖死，必须能被 TCP 层掐断。
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable();
        if (builder != null) {
            apiBuilder.restClientBuilder(builder);
        }
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }
}
