package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.llm.FallbackLlmProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量化入口：<b>只有严格路径</b>——入库与检索都经 {@link #embedBatchStrict}，
 * 真实 embedding 服务重试后仍失败就抛异常，由上层把文档标 FAILED 或让问答明确报错。
 * <p>原先这里还有一个可降级的 {@code embedBatch/embedOne}（真服务故障时静默返回哈希向量），
 * 已随 D-14/D-28 删除：哈希向量与真向量<b>同为 1024 维</b>，混进去不报任何错、事后无从分辨，
 * 唯一后果是检索质量静默变差。要跑通本机链路请用 {@code app.llm.allow-hash-vectors=true}
 * 显式开启（伴随 WARN），而不是靠这条静默兜底。
 */
@Service
public class EmbeddingService {

    private final FallbackLlmProvider provider;

    public EmbeddingService(FallbackLlmProvider provider) {
        this.provider = provider;
    }

    /** 唯一入口：不降级，失败抛出 */
    public List<double[]> embedBatchStrict(List<String> texts) {
        return provider.embedStrict(texts);
    }

    /**
     * 实现名，日志/监控/评测快照用：openai-embedding:&lt;model&gt; 或 hash-fallback。
     * hash-fallback 现在只在 allow-hash-vectors=true 时才会真的被用到。
     */
    public String name() {
        return provider.real() ? "openai-embedding:" + provider.embeddingModel() : "hash-fallback";
    }

    /** provider 当前实际生效的嵌入模型名：真实服务是配置值，mock 实现返回 mock-hash-&lt;维度&gt; */
    public String model() {
        return provider.embeddingModel();
    }
}
