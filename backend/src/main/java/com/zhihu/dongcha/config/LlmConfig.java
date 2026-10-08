package com.zhihu.dongcha.config;

import com.zhihu.dongcha.llm.FallbackLlmProvider;
import com.zhihu.dongcha.llm.LlmProvider;
import com.zhihu.dongcha.llm.MockLlmProvider;
import com.zhihu.dongcha.llm.OpenAiCompatibleLlmProvider;
import com.zhihu.dongcha.rag.HashEmbedder;
import com.zhihu.dongcha.redis.CostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM/Embedding 实现装配：app.llm.provider=openai（默认）走 OpenAI 兼容接口，
 * =mock 或启动探针失败自动降级 MockLlmProvider（无外网也能跑通链路）。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** 装出 MockLlmProvider（本地模板合成答案+哈希向量），作真实 LLM 的兜底实现常驻容器。*/
    @Bean
    public MockLlmProvider mockLlmProvider(HashEmbedder hashEmbedder) {
        return new MockLlmProvider(hashEmbedder);
    }

    /** 装出主用的 FallbackLlmProvider：provider=openai 且有 apiKey 用真实实现（可选探活），否则降级 Mock 并置 availability.llm=false。*/
    @Bean
    public FallbackLlmProvider llmProvider(Availability availability,
                                           MockLlmProvider mock,
                                           CostService cost,
                                           @Value("${app.llm.provider}") String provider,
                                           @Value("${app.llm.base-url}") String baseUrl,
                                           @Value("${app.llm.api-key}") String apiKey,
                                           @Value("${app.llm.chat-model}") String chatModel,
                                           @Value("${app.llm.embedding-model}") String embeddingModel,
                                           @Value("${app.llm.embedding-dim}") int embeddingDim,
                                           @Value("${app.llm.embedding-batch-size}") int embedBatch,
                                           @Value("${app.llm.temperature}") double temperature,
                                           @Value("${app.llm.timeout-seconds}") int timeoutSeconds,
                                           @Value("${app.llm.probe-on-startup}") boolean probe,
                                           @Value("${app.llm.allow-hash-vectors}") boolean allowHashVectors) {
        LlmProvider primary;
        if (!"openai".equalsIgnoreCase(provider)) {
            availability.llm = false;
            log.warn("[health] app.llm.provider={}，问答与向量使用 MockLlmProvider", provider);
            primary = mock;
        } else if (apiKey == null || apiKey.isBlank()) {
            availability.llm = false;
            log.error("[health] app.llm.provider=openai 但未注入 LLM_API_KEY，问答使用 MockLlmProvider；"
                    + "向量化默认**拒绝入库**（D-14：哈希假向量维度与真向量一致、入库不报错却静默污染检索质量），"
                    + "只有 app.llm.allow-hash-vectors=true 才允许写假向量");
            primary = mock;
        } else {
            OpenAiCompatibleLlmProvider real = new OpenAiCompatibleLlmProvider(
                    baseUrl, apiKey, chatModel, embeddingModel, embeddingDim, embedBatch,
                    temperature, timeoutSeconds, cost);
            availability.llm = true;
            if (probe) {
                try {
                    long t0 = System.currentTimeMillis();
                    real.probe();
                    log.info("[health] LLM/embedding 就绪: {} (chat={}, embedding={} dim={}, 耗时 {}ms)",
                            baseUrl, chatModel, embeddingModel, embeddingDim, System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    availability.llm = false;
                    log.warn("[health] LLM/embedding 探活失败，降级 MockLlmProvider: {}", e.toString());
                }
            } else {
                log.info("[health] 跳过 LLM 启动探活（app.llm.probe-on-startup=false）");
            }
            primary = real;
        }
        return new FallbackLlmProvider(primary, mock, availability, allowHashVectors);
    }
}
