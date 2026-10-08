package com.zhihu.dongcha.llm;

import com.zhihu.dongcha.config.Availability;
import com.zhihu.dongcha.rag.ChunkHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LlmProvider 门面：按 Availability 与真实调用结果在"真实接口 / Mock 回退"之间路由。
 * 真实接口抛 LlmUnavailableException 时打印 WARN 并即时降级（当次调用即用 mock 结果，不阻塞业务）。
 */
public class FallbackLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmProvider.class);

    private final LlmProvider primary;    // 可为 mock（app.llm.provider=mock 时二者相同）
    private final MockLlmProvider mock;
    private final Availability availability;
    private final boolean allowHashVectors;

    /** 由 LlmConfig 装配：provider=mock 或未注入密钥时 primary 就是 mock 本身；探活失败时 primary 仍是真实现，靠降级位关掉 */
    public FallbackLlmProvider(LlmProvider primary, MockLlmProvider mock, Availability availability,
                               boolean allowHashVectors) {
        this.primary = primary;
        this.mock = mock;
        this.availability = availability;
        this.allowHashVectors = allowHashVectors;
    }

    /** 降级位 availability.llm 与 primary.real() 任一为假，本次调用就整条走 mock */
    private boolean useReal() {
        return availability.llm && primary.real();
    }

    /** ChatService 与 EvalService 的合成入口，下游只读每块的 text；真实流报错时 onErrorResume 续上 mock 流 */
    @Override
    public Flux<LlmChunk> streamSynthesis(String question, List<ChunkHit> hits, List<Turn> history,
                                          String retryNote, double temperature) {
        if (!useReal()) return mock.streamSynthesis(question, hits, history, retryNote, temperature);
        return primary.streamSynthesis(question, hits, history, retryNote, temperature)
                .onErrorResume(e -> {
                    degrade("streamSynthesis", e);
                    return mock.streamSynthesis(question, hits, history, retryNote, temperature);
                });
    }

    /** JudgeService 两处打分的调用点；失败即换 mock，mock 的空串会被解析成"未判定"而不是继续抛错 */
    @Override
    public LlmResult complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        if (!useReal()) return mock.complete(systemPrompt, userPrompt, temperature, maxTokens);
        try {
            return primary.complete(systemPrompt, userPrompt, temperature, maxTokens);
        } catch (RuntimeException e) {
            degrade("complete", e);
            return mock.complete(systemPrompt, userPrompt, temperature, maxTokens);
        }
    }

    /**
     * {@link LlmProvider} 契约要求的容错嵌入：真实服务故障时静默返回 mock 哈希向量。
     * <b>入库与检索都不要用它</b>（D-14/D-28 之后这两条链一律走 {@link #embedStrict}）——
     * 保留只是因为门面要实现同一个接口，当前全仓无调用方。
     */
    @Override
    public List<double[]> embed(List<String> texts) {
        if (!useReal()) return mock.embed(texts);
        try {
            return primary.embed(texts);
        } catch (RuntimeException e) {
            degrade("embed", e);
            return mock.embed(texts);
        }
    }

    /**
     * 严格路径（入库 / 问答检索向量化）：不吞异常，失败向上抛出。
     * 配置为真实服务时始终调用真实接口（不受 availability.llm 降级位影响），
     * 成功即自愈该位，避免瞬时故障后永久停留在哈希向量。
     *
     * D-14：没有真实 embedding 时**默认拒绝**。哈希向量维度与真向量一致，写进去不报错，
     * 但检索质量静默变差且事后无法与真向量区分——所以宁可让文档停在 FAILED，
     * 也不要"看起来入库成功"。只有显式 {@code app.llm.allow-hash-vectors=true}
     * （本机无密钥要把链路跑通时）才继续用 mock 嵌入，并且每次都打 WARN。
     */
    public List<double[]> embedStrict(List<String> texts) {
        if (!primary.real()) {
            if (!allowHashVectors) {
                throw new HashVectorRejectedException(
                        "当前没有可用的真实 embedding 服务（app.llm.provider=" + providerKind() + "）。"
                                + "拒绝写入 1024 维哈希假向量：它维度与真向量一致、入库不报错，却会静默污染检索质量且事后无法区分。"
                                + "请注入 LLM_API_KEY 后重试；本机确实要用假向量跑通链路时，显式设 app.llm.allow-hash-vectors=true");
            }
            log.warn("[embedding] 未配置真实 embedding，按 allow-hash-vectors=true 写入**哈希假向量**（检索质量不可信，不要用于生产）");
            return mock.embed(texts);
        }
        List<double[]> vectors = primary.embed(texts);
        if (!availability.llm) {
            availability.llm = true;
            log.info("[llm] 严格调用成功，availability.llm 自愈恢复为 true");
        }
        return vectors;
    }

    /** 只用于报错文案：区分"配置成 mock"与"配置 openai 但没密钥" */
    private String providerKind() {
        return primary instanceof MockLlmProvider ? "mock（或未注入 LLM_API_KEY 而回退）" : "unknown";
    }

    /** ChatService 的 intent 步骤文案：两边都不调模型，只是措辞不同（真实侧须写明"未调用模型"） */
    @Override
    public String intentDetail(String question) {
        return useReal() ? primary.intentDetail(question) : mock.intentDetail(question);
    }

    /** done 事件的 model 字段、问答缓存与评测快照都取这里，降级期间返回 "mock" */
    @Override
    public String chatModel() {
        return useReal() ? primary.chatModel() : mock.chatModel();
    }

    /** EmbeddingService 用它拼向量实现名，降级时是 mock-hash-1024，可据此辨别假向量来源 */
    @Override
    public String embeddingModel() {
        return useReal() ? primary.embeddingModel() : mock.embeddingModel();
    }

    /** 上层据此决定"跳过 LLM 判定 / 标注降级"；配置是真模型但降级位关闭时同样返回 false */
    @Override
    public boolean real() {
        return useReal();
    }

    /** 只透给 primary（mock 走接口默认空实现），不重置 availability.llm */
    @Override
    public void applyConfig(String baseUrl, String chatModel, String embeddingModel) {
        primary.applyConfig(baseUrl, chatModel, embeddingModel);
    }

    /** 每次失败都把降级位置 false；降级期间的重复失败不再刷 WARN，真实侧调用成功由 embedStrict 自愈 */
    private void degrade(String op, Throwable e) {
        if (availability.llm) {
            log.warn("[llm] {} 调用失败，问答/嵌入降级为 MockLlmProvider: {}", op, e.toString());
        }
        availability.llm = false;
    }
}
