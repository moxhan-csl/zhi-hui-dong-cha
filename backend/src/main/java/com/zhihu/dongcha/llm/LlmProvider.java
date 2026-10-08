package com.zhihu.dongcha.llm;

import com.zhihu.dongcha.rag.ChunkHit;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 能力抽象：真实实现走 OpenAI 兼容接口（千问 DashScope），
 * 回退实现走 MockLlmProvider（模板拼装 + 哈希向量）。
 */
public interface LlmProvider {

    /**
     * 多轮上下文里的一条历史消息。只能由服务端会话记录（{@code Conversation.messages}）构造，
     * 不接受客户端上传——否则任何人都能往模型里塞指令。
     */
    record Turn(String role, String content) {
    }

    /**
     * 问答 synthesis 阶段：基于检索片段流式生成带 [n] 行内引用的回答；{@code history} 为该会话的最近若干轮。
     * <p>{@code retryNote} 是 D-1 的**重生成指令**（上一轮未通过质量校验时说明原因并要求改做），
     * 首轮传 null。它的唯一作用就是让第二次采样与第一次不同——同 prompt 同温度重跑一次只会拿到几乎一样的文本，
     * 那不是重试，是白烧一次调用。
     */
    Flux<LlmChunk> streamSynthesis(String question, List<ChunkHit> hits, List<Turn> history,
                                   String retryNote, double temperature);

    /** 一次性补全（质量验证、LLM-as-Judge 等短任务） */
    LlmResult complete(String systemPrompt, String userPrompt, double temperature, int maxTokens);

    /** 批量向量化（阻塞调用，仅在 boundedElastic / 工作线程上使用） */
    List<double[]> embed(List<String> texts);

    /** 意图识别阶段的展示文案（不参与模型调用，保持事件协议稳定） */
    String intentDetail(String question);

    /** 真实模型名（mock 返回 "mock"） */
    String chatModel();

    /** 嵌入模型名（mock 为 mock-hash-<维度>）：EmbeddingService 据此标注本次向量的真实来源 */
    String embeddingModel();

    /** 是否真实模型 */
    boolean real();

    /**
     * 应用热配置（PUT /settings/models 或启动时从 MySQL settings_models 读取）：
     * baseUrl 变化会重建 HTTP 客户端；mock 实现忽略。
     */
    default void applyConfig(String baseUrl, String chatModel, String embeddingModel) {
    }
}
