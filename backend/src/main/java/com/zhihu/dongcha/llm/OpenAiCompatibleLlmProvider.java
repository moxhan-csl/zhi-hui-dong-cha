package com.zhihu.dongcha.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.rag.ChunkHit;
import com.zhihu.dongcha.redis.CostService;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;

/**
 * OpenAI 兼容接口实现（千问 DashScope compatible-mode）：
 * - POST /chat/completions stream=true：逐块解析 delta.content，尾块 usage 计入成本；
 * - POST /chat/completions 非流式：质量验证与评测 LLM-as-Judge；
 * - POST /embeddings：批量向量化（单批上限可配，DashScope 兼容模式上限 10），失败重试 2 次。
 */
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final CostService cost;
    private volatile String chatModel;
    private volatile String embeddingModel;
    private volatile String baseUrl;
    private final String apiKey;
    private volatile WebClient http;
    private final int embeddingDim;
    private final int embedBatchSize;
    private final double defaultTemperature;
    private final Duration requestTimeout;

    /** 由 LlmConfig 装配：单批条数夹到 1..10（DashScope 兼容模式上限），超时秒数下限 10 秒 */
    public OpenAiCompatibleLlmProvider(String baseUrl, String apiKey, String chatModel, String embeddingModel,
                                       int embeddingDim, int embedBatchSize, double defaultTemperature,
                                       int timeoutSeconds, CostService cost) {
        this.cost = cost;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.embeddingDim = embeddingDim;
        this.embedBatchSize = Math.max(1, Math.min(embedBatchSize, 10));
        this.defaultTemperature = defaultTemperature;
        this.requestTimeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
        this.http = buildClient();
    }

    /** 热切换模型与地址（PUT /settings/models）；baseUrl 变化重建 WebClient */
    @Override
    public synchronized void applyConfig(String newBaseUrl, String newChatModel, String newEmbeddingModel) {
        boolean urlChanged = newBaseUrl != null && !newBaseUrl.isBlank() && !newBaseUrl.equals(this.baseUrl);
        if (newChatModel != null && !newChatModel.isBlank()) this.chatModel = newChatModel;
        if (newEmbeddingModel != null && !newEmbeddingModel.isBlank()) this.embeddingModel = newEmbeddingModel;
        if (urlChanged) {
            this.baseUrl = newBaseUrl;
            this.http = buildClient();
            log.info("LLM base-url 已热切换为 {}", newBaseUrl);
        }
    }

    /** base-url 与 Bearer 头在构造时固化，所以热改地址必须重建客户端；响应体缓冲上限 16MB */
    private WebClient buildClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 8000)
                        .responseTimeout(requestTimeout)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    // ==================== chat ====================

    /** SSE 逐块只取 choices[0].delta.content，单块解析失败只 warn 跳过、不中断整条流；用量在 doOnComplete 记账，不向下发 usage 块 */
    @Override
    public Flux<LlmChunk> streamSynthesis(String question, List<ChunkHit> hits, List<Turn> history,
                                          String retryNote, double temperature) {
        // 重生成指令拼在本轮 user prompt 末尾（不进 system，避免污染会话里其它用途的 system 文案）
        String prompt = SynthesisPrompt.userPrompt(question, hits)
                + (retryNote == null || retryNote.isBlank() ? "" : retryNote);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SynthesisPrompt.systemPrompt()));
        StringBuilder promptText = new StringBuilder(SynthesisPrompt.systemPrompt());
        if (history != null) {
            for (Turn t : history) {
                messages.add(Map.of("role", t.role(), "content", t.content()));
                promptText.append('\n').append(t.role()).append('=').append(t.content());
            }
        }
        messages.add(Map.of("role", "user", "content", prompt));
        promptText.append('\n').append(prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("messages", messages);
        // temperature<=0 一律当作"未指定"退回配置默认值；本次生成上限写死 900 token，不读配置
        body.put("temperature", temperature <= 0 ? defaultTemperature : temperature);
        body.put("max_tokens", 900);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        UsageCollector usage = new UsageCollector();
        StringBuilder emitted = new StringBuilder();
        return http.post().uri("/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("").flatMap(b ->
                        Mono.error(new IllegalStateException("LLM HTTP " + r.statusCode() + ": " + clip(b)))))
                .bodyToFlux(SSE_TYPE)
                .timeout(requestTimeout)
                .<LlmChunk>handle((raw, sink) -> {
                    String data = raw.data();
                    if (data == null || data.isBlank() || "[DONE]".equals(data.strip())) return;
                    try {
                        JsonNode node = readTree(data);
                        JsonNode choice = node.path("choices").path(0);
                        String piece = choice.path("delta").path("content").asText("");
                        JsonNode u = node.path("usage");
                        if (!u.isMissingNode() && !u.isNull()) {
                            usage.collect(u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0));
                        }
                        if (!piece.isEmpty()) {
                            emitted.append(piece);
                            sink.next(LlmChunk.text(piece));
                        }
                    } catch (Exception e) {
                        log.warn("stream chunk skipped: {}", e.toString());
                    }
                })
                .doOnComplete(() -> {
                    // 估算基数是全部消息（含历史），只算本轮 prompt 会漏记上下文 token
                    LlmUsage u = usage.finish(promptText.toString(), emitted.toString());
                    if (u != null) cost.record(chatModel, u).subscribe();
                })
                .doOnError(e -> log.warn("LLM stream error: {}", e.toString()));
    }

    /** 非流式补全（质量验证、评测 Judge 用）：block 等整个响应，任何运行时异常都包成 LlmUnavailableException 交门面降级 */
    @Override
    public LlmResult complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        // 与流式同一口径：JudgeService 传的 temperature=0 在这里被当作"未指定"，换成配置的默认温度
        body.put("temperature", temperature <= 0 ? defaultTemperature : temperature);
        body.put("max_tokens", maxTokens <= 0 ? 300 : maxTokens);
        try {
            String json = http.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("").flatMap(b ->
                            Mono.error(new IllegalStateException("LLM HTTP " + r.statusCode() + ": " + clip(b)))))
                    .bodyToMono(String.class)
                    .block(requestTimeout);
            JsonNode root = readTree(json);
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode u = root.path("usage");
            LlmUsage usage = u.isMissingNode() || u.isNull()
                    ? new LlmUsage(estimate(systemPrompt + userPrompt), estimate(text), true)
                    : new LlmUsage(u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0), false);
            cost.record(chatModel, usage).subscribe();
            return new LlmResult(text, usage);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("chat/completions 调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== embeddings ====================

    /** 按 embedBatchSize 串行分批；某批三轮仍失败就整次抛错，不会只返回一部分向量 */
    @Override
    public List<double[]> embed(List<String> texts) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += embedBatchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + embedBatchSize));
            out.addAll(embedBatchWithRetry(batch));
        }
        return out;
    }

    /** 共三轮尝试，间隔 400ms×轮次且阻塞当前线程；最后一轮失败后仍会先睡满再抛 LlmUnavailableException */
    private List<double[]> embedBatchWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= 2; attempt++) {   // 首次 + 重试 2 次
            try {
                return embedBatch(batch);
            } catch (RuntimeException e) {
                last = e;
                log.warn("embedding batch 第 {} 次尝试失败(size={}): {}", attempt + 1, batch.size(), e.toString());
                sleepQuietly(400L * (attempt + 1));
            }
        }
        throw new LlmUnavailableException("embedding 调用失败（重试 2 次后放弃）: " + msg(last), last);
    }

    /** 单次 /embeddings 请求：只按 data 数组顺序收集、不读每项的 index，因此数量与入参不符就当作失败抛给重试层 */
    private List<double[]> embedBatch(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", batch);
        body.put("dimensions", embeddingDim);
        body.put("encoding_format", "float");
        try {
            String json = http.post().uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("").flatMap(b ->
                            Mono.error(new IllegalStateException("embeddings HTTP " + r.statusCode() + ": " + clip(b)))))
                    .bodyToMono(String.class)
                    .block(requestTimeout);
            JsonNode root = readTree(json);
            List<double[]> vectors = new ArrayList<>();
            root.path("data").forEach(n -> {
                JsonNode emb = n.path("embedding");
                double[] v = new double[emb.size()];
                for (int i = 0; i < emb.size(); i++) v[i] = emb.get(i).asDouble();
                vectors.add(v);
            });
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回数量不匹配: " + vectors.size() + " != " + batch.size());
            }
            long tokens = root.path("usage").path("total_tokens").asLong(0);
            if (tokens > 0) cost.record(embeddingModel, new LlmUsage(tokens, 0, false)).subscribe();
            return vectors;
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("embeddings 调用失败: " + e.getMessage(), e);
        }
    }

    /** 启动探针：一次最小 embedding 请求，验证 base-url / api-key / 模型可用 */
    public void probe() {
        List<double[]> v = embedBatch(List.of("ping"));
        if (v.isEmpty() || v.get(0).length != embeddingDim) {
            throw new IllegalStateException("embedding 维度不符合预期: " + (v.isEmpty() ? 0 : v.get(0).length));
        }
    }

    // ==================== misc ====================

    @Override
    public String intentDetail(String question) {
        // 意图阶段是关键词规则匹配，不调用模型（额外消耗 token 换取展示文案不划算）。
        // 文案必须说清"规则"，把规则结果写成"（模型名）识别为"等于谎报模型行为。
        if (question.matches(".*(流程|制度|规定|规范|申请|报销|审批|入职|考勤|采购).*"))
            return "关键词规则命中「制度流程咨询」意图（未调用模型），已限定可见知识库范围";
        if (question.matches(".*(架构|部署|接口|版本|组件|模块|配置|运维).*"))
            return "关键词规则命中「产品技术问题」意图（未调用模型），已限定可见知识库范围";
        if (question.matches(".*(战略|利润|成本|预算|财报|经营).*"))
            return "关键词规则命中「经营与财务」意图（未调用模型），已限定可见知识库范围";
        return "无关键词命中，按「通用知识问答」意图处理（未调用模型），已限定可见知识库范围";
    }

    /** volatile：热切后调用方立即读到新名字；成本是按天累计的，模型名只出现在成本日志里 */
    @Override
    public String chatModel() {
        return chatModel;
    }

    /** embedBatch 记 embedding 用量时传的就是它，与 chat 用量累进同一个按天计数 */
    @Override
    public String embeddingModel() {
        return embeddingModel;
    }

    /** 恒为真：只表达"本实现是真实接口"这一配置层事实，某次调用失败由降级位反映 */
    @Override
    public boolean real() {
        return true;
    }

    // ---------- helpers ----------

    /** 空响应体按 {} 解析，让下游 path(...) 链取不到字段时落回默认值而不是抛异常 */
    private static JsonNode readTree(String json) {
        try {
            return Json.MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 服务没回 usage 时的粗估：字符数×0.75，结果标 estimated=true 单独累计 */
    private static long estimate(String s) {
        return s == null || s.isEmpty() ? 0 : Math.max(1, Math.round(s.length() * 0.75));
    }

    /** 异常消息里只留响应体前 300 字符，免得整页错误内容刷进日志 */
    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String msg(Throwable e) {
        return e == null ? "unknown" : String.valueOf(e.getMessage());
    }

    /** 重试间隔：被中断时恢复中断标记后照常继续下一轮尝试，不提前放弃 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 流式尾块 usage 收集；服务未回 usage 时按字符数估算 */
    private static final class UsageCollector {
        private long prompt;
        private long completion;
        private boolean seen;

        /** 只接受非零值并覆盖旧值，因此以服务端最后回传的那一块 usage 为准 */
        synchronized void collect(long p, long c) {
            if (p > 0) prompt = p;
            if (c > 0) completion = c;
            if (p > 0 || c > 0) seen = true;
        }

        /** 见过真实 usage 就用真实的；否则按字符估算，估算为 0 时返回 null，调用方据此不记成本 */
        synchronized LlmUsage finish(String promptText, String answerText) {
            if (seen) return new LlmUsage(prompt, completion, false);
            long p = estimate(promptText);
            long a = estimate(answerText);
            return p + a == 0 ? null : new LlmUsage(p, a, true);
        }
    }
}
