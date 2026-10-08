package com.zhihu.dongcha.chat;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.config.Availability;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.llm.FallbackLlmProvider;
import com.zhihu.dongcha.llm.JudgeService;
import com.zhihu.dongcha.llm.LlmProvider;
import com.zhihu.dongcha.llm.SynthesisPrompt;
import com.zhihu.dongcha.model.AppConfig;
import com.zhihu.dongcha.model.ChatMessage;
import com.zhihu.dongcha.model.Conversation;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.rag.ChunkHit;
import com.zhihu.dongcha.rag.RetrievalService;
import com.zhihu.dongcha.rag.Visibility;
import com.zhihu.dongcha.redis.ChatCache;
import com.zhihu.dongcha.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能问答 SSE 编排：start → intent → retrieval → synthesis（token / citation）→ validation → done。
 * <p>真实链路：检索走 pgvector + text-embedding-v3，synthesis 走 qwen-plus 流式生成（token 逐块下发），
 * 质量验证走一次轻量 LLM 忠实度判定（失败降级通过，不阻塞回答）；
 * 判定未达阈值时带改进指令重新生成（D-1，上限 {@code app.chat.quality-retries}，只重生成不重检索），
 * 重生成前会先下发 {@code revision} 事件告知前端"上一版作废"。
 * <p>citation 一律由后端检索结果生成并与行内 [n] 对齐，模型编造的越界编号会被规整剔除。
 * <p>多轮上下文（D-11）只取服务端会话记录，不进请求体；那段历史同时参与回答缓存的 key。
 * <p>整条流水线在 boundedElastic 线程上以阻塞方式编排（JPA / JDBC / Redis / LLM 均为阻塞调用），
 * 事件下发协议与旧版完全一致；同一用户新提问会打断旧流。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 请求体不带历史（D-11）：上下文只能来自服务端自己的会话记录，否则客户端可以往模型里塞指令 */
    public record ChatRequest(String conversationId, String question, List<String> knowledgeBaseIds) {
    }

    private final Store store;
    private final DbStore db;
    private final RetrievalService retrieval;
    private final FallbackLlmProvider llm;
    private final Visibility visibility;
    private final JudgeService judge;
    private final ChatCache cache;
    private final Availability availability;
    private final ChatGuard guard;
    private final int contextTurns;
    /** 质量校验未通过时的重生成上限（D-1）：0 = 只生成一次，即旧的"只标不重试"行为 */
    private final int qualityRetries;

    /** userId -> 当前流 sink（用于打断旧流） */
    private final Map<String, FluxSink<ServerSentEvent<String>>> running = new ConcurrentHashMap<>();

    public ChatService(Store store, DbStore db, RetrievalService retrieval, FallbackLlmProvider llm,
                       Visibility visibility, JudgeService judge, ChatCache cache, Availability availability,
                       ChatGuard guard,
                       @Value("${app.chat.context-turns}") int contextTurns,
                       @Value("${app.chat.quality-retries}") int qualityRetries) {
        this.store = store;
        this.db = db;
        this.retrieval = retrieval;
        this.llm = llm;
        this.visibility = visibility;
        this.judge = judge;
        this.cache = cache;
        this.availability = availability;
        this.guard = guard;
        this.contextTurns = Math.max(0, contextTurns);
        // 钳制在 0-2：每次重试是"一次合成 + 一次判定"的真金白银，上限 2 已是单问最多 3 轮模型调用
        this.qualityRetries = Math.min(2, Math.max(0, qualityRetries));
    }

    /** 问答主入口：ChatGuard 占在途名额→解析会话上下文→探测回答缓存，命中走 replay、未命中走 live，流终止时归还名额 */
    public Flux<ServerSentEvent<String>> stream(User user, ChatRequest req) {
        String q = req.question() == null ? "" : req.question().trim();
        if (q.isEmpty() || q.length() > 500) {
            throw BizException.badRequest("问题需为 1-500 字");
        }
        // D-31/D-32：护栏在取缓存之前判，被拒的提问不会占用 worker、也不会命中/回填缓存。
        // 拒绝以 SSE error 事件下发（text/event-stream 的状态行此时已提交，给不了 429 状态码），
        // 载荷带 code/retryAfter，前端 error 气泡与重试按钮无需改动即可用。
        return guard.acquire(user.id)
                .flatMapMany(release -> resolveContext(user, req, q)
                        .flatMapMany(ctx -> cache.probe(q, ctx.scope().scopes(), ctx.histKey())
                                .flatMapMany(p -> p.hit()
                                        ? replay(ctx, q, p.cached().get())
                                        : live(ctx, user, q, p.gen())))
                        .doFinally(sig -> guard.releaseWith(release)))
                .onErrorResume(BizException.class, this::rejected)
                .onErrorResume(e -> {
                    log.warn("chat stream failed: {}", e.toString());
                    return Flux.just(sse("error", Map.of("message", "生成失败: " + e.getMessage(), "retryable", true)));
                });
    }

    /** 本次提问的会话、可见范围与上下文：回放、实时、回填三条链路共用同一份 */
    private record Ctx(Conversation conv, List<LlmProvider.Turn> history, String histKey, Scope scope) {
    }

    /**
     * 会话解析与历史窗口必须在查缓存之前定下来（D-11）：历史进 prompt，也就进缓存 key，
     * 否则同一个问题在追问语境下会拿首轮答案作答。
     * <p>范围裁剪（scopeOf）也在这条链上：D-30 之后可见性判定每请求直读 MySQL，
     * 而 stream() 是在 Netty 事件循环上被调用的——放在这里就是放在 boundedElastic 上，
     * 放在方法开头那次同步求值里就会把一次 DB 往返摁在事件循环上。三者都是阻塞调用，统一 deferred。
     */
    private Mono<Ctx> resolveContext(User user, ChatRequest req, String question) {
        return Mono.fromCallable(() -> {
            Scope scope = scopeOf(user, req.knowledgeBaseIds());
            Conversation conv = resolveConversation(user, req, question, scope);
            List<LlmProvider.Turn> history = SynthesisPrompt.historyOf(conv.messages, contextTurns);
            return new Ctx(conv, history, historyKey(conv), scope);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 参与本轮 prompt 的那段消息 id 窗口，作缓存 key 的上下文指纹；无历史时为空串（首轮仍可跨会话命中） */
    private String historyKey(Conversation conv) {
        List<ChatMessage> ms = conv.messages;
        int size = ms.size();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, size - contextTurns * 2); i < size; i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ms.get(i).id);
        }
        return sb.toString();
    }

    /** 闸门拒绝的 BizException 转成 SSE error 事件载荷：带 code/retryAfter，因为 text/event-stream 已无法回 429 状态码 */
    private Flux<ServerSentEvent<String>> rejected(BizException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", e.getMessage());
        data.put("retryable", true);
        data.put("code", e.code);
        if (e.retryAfter != null) data.put("retryAfter", e.retryAfter);
        return Flux.just(sse("error", data));
    }

    // ==================== 缓存命中回放 ====================

    /** 缓存命中回放：下发 start + intent/retrieval/synthesis 三段 skipped 的 agent-update，整段答案一次性发 token，并照常落库 */
    private Flux<ServerSentEvent<String>> replay(Ctx ctx, String q, ChatCache.Cached cached) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
            Conversation conv = ctx.conv();
            Scope scope = ctx.scope();
            String messageId = AppConfig.newId();
            sink.next(sse("start", Map.of("conversationId", conv.id, "messageId", messageId)));
            // 缓存命中确实跳过了意图/检索/生成三个阶段，状态如实标 skipped，不伪装成"已完成"
            String skipDetail = "命中回答缓存（Redis TTL " + cache.ttlSeconds() + "s），未执行检索与模型生成";
            for (String stage : List.of("intent", "retrieval", "synthesis")) {
                // 范围裁剪提示只挂在 retrieval 阶段一次，与实时链路的位置保持一致
                String detail = "retrieval".equals(stage) && scope.note() != null
                        ? scope.note() + "；" + skipDetail : skipDetail;
                sink.next(sse("agent-update", Map.of("step", stage, "status", "skipped", "detail", detail)));
            }
            sink.next(sse("agent-update", Map.of("step", "validation", "status",
                    cached.qualityPassed() ? "done" : "failed",
                    "detail", "沿用缓存中的质量校验结论")));
            sink.next(sse("token", Map.of("text", cached.answer() == null ? "" : cached.answer())));
            if (cached.citations() != null && !cached.citations().isEmpty()) {
                sink.next(sse("citation", cached.citations()));
            }
            persistMessages(conv, q, cached.answer(), cached.citations());
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("qualityPassed", cached.qualityPassed());
            done.put("conversationId", conv.id);
            done.put("messageId", messageId);
            done.put("cached", true);
            sink.next(sse("done", done));
            sink.complete();
        }, FluxSink.OverflowStrategy.BUFFER).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 实时流水线 ====================

    /** 实时链路入口：把本流登记进 running 并用 error+complete 打断同用户的旧流，再在 boundedElastic 上跑 pipeline */
    private Flux<ServerSentEvent<String>> live(Ctx ctx, User user, String q, long gen) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
            var old = running.put(user.id, sink);
            if (old != null && !old.isCancelled()) {
                old.next(sse("error", Map.of("message", "已被新的提问打断", "retryable", false)));
                old.complete();
            }
            try {
                pipeline(sink, ctx, user, q, gen);
            } catch (Exception e) {
                log.warn("chat pipeline error", e);
                if (!sink.isCancelled()) {
                    sink.next(sse("error", Map.of("message", "生成失败: " + e.getMessage(), "retryable", true)));
                }
            } finally {
                running.remove(user.id, sink);
                if (!sink.isCancelled()) sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER).subscribeOn(Schedulers.boundedElastic());
    }

    /** 阻塞式全链路编排：意图→检索→合成/质量验证（可重试）→落库→通过时回填缓存→done，每段后检查客户端是否已断开 */
    private void pipeline(FluxSink<ServerSentEvent<String>> sink, Ctx ctx, User user, String question,
                          long gen) {
        List<String> scopes = ctx.scope().scopes();
        Scope scope = ctx.scope();
        Conversation conv = ctx.conv();
        String messageId = AppConfig.newId();
        sink.next(sse("start", Map.of("conversationId", conv.id, "messageId", messageId)));
        if (cancelled(sink)) return;

        // 1. 意图识别（规则匹配，见 LlmProvider.intentDetail）
        step(sink, "intent", "running", null);
        step(sink, "intent", "done", llm.intentDetail(question));
        if (cancelled(sink)) return;

        // 2. 检索规划（真实向量 + pgvector；失败自动重试，仍失败则明确报错、不进入生成）
        step(sink, "retrieval", "running", scope.note() == null
                ? "正在向量库中检索相关知识…" : scope.note() + "正在向量库中检索相关知识…");
        List<ChunkHit> hits;
        try {
            hits = retrieval.retrieveForChat(user, question, scopes,
                    n -> step(sink, "retrieval", "running", "检索失败，正在第 " + (n + 1) + " 次重试…"));
        } catch (RetrievalService.RetrievalFailedException e) {
            log.warn("retrieval failed after retries", e);
            step(sink, "retrieval", "failed", "检索服务异常（重试后仍失败），本次不生成回答");
            sink.next(sse("error", Map.of(
                    "message", "检索服务暂时不可用，已自动重试仍未成功，本次未生成回答。请点击「重试」或稍后再试。",
                    "retryable", true)));
            return;
        }
        step(sink, "retrieval", "done", (scope.note() == null ? "" : scope.note() + "。")
                + (hits.isEmpty() ? "未命中任何片段"
                : String.format("在 %d 个可见知识库中命中 %d 个片段（%s + %s，阈值 %.2f）",
                scopes.size(), hits.size(), retrieval.vectorMode(), retrieval.embedMode(),
                store.config.rag.scoreThreshold)));
        if (cancelled(sink)) return;

        // 2.1 零命中：不调用模型作答（防幻觉），直接下发拒答提示
        if (hits.isEmpty()) {
            sink.next(sse("warning", Map.of("type", "no-result")));
            refuseNoHit(sink, conv, question, messageId);
            return;
        }

        // 3. 知识合成 + 4. 质量验证（D-1：校验未通过时带改进指令重生成，上限 app.chat.quality-retries）
        // 只重生成、不重检索：同一问题同一范围再检一次还是那批片段，重试唯一能变的是采样，
        // 所以改进指令必须进 prompt（见 SynthesisPrompt.retryDirective），否则就是原样再烧一次调用。
        // 降级到本地模板时不重试——模板输出只由片段决定，同输入同输出，重试没有意义只会多花一次判定。
        List<Map<String, Object>> citations = SynthesisPrompt.citations(hits);
        // mock 模板不吃上下文（见 MockLlmProvider），所以只有真实模型在用时才标注"带上下文"，否则是谎报
        String ctxNote = llm.real() && !ctx.history().isEmpty()
                ? "（本轮带 " + ctx.history().size() + " 条会话上下文）" : "";
        int maxAttempts = llm.real() ? qualityRetries + 1 : 1;
        String retryNote = null;
        String finalAnswer = "";
        JudgeService.Verdict verdict = null;
        boolean qualityPassed = false;
        int attempt = 0;
        while (attempt < maxAttempts && !cancelled(sink)) {
            attempt++;
            if (attempt > 1) {
                // 上一版已经逐 token 下发给客户端了，不先声明作废就会把两版拼在同一条消息里
                sink.next(sse("revision", Map.of("attempt", attempt, "reason", verdict.reason())));
            }
            step(sink, "synthesis", "running", (llm.real()
                    ? llm.chatModel() + " 流式生成中" + ctxNote
                    + (attempt > 1 ? "（第 " + attempt + " 次生成，带改进指令）" : "") + "…"
                    : "LLM 不可用，使用本地模板合成（降级）"));
            StringBuilder answer = new StringBuilder();
            try {
                llm.streamSynthesis(question, hits, ctx.history(), retryNote,
                        store.config.models.chat.temperature)
                        .takeWhile(c -> !cancelled(sink))
                        .toStream()
                        .forEach(c -> {
                            if (c.text() == null || c.text().isEmpty()) return;
                            answer.append(c.text());
                            sink.next(sse("token", Map.of("text", c.text())));
                        });
            } catch (Exception e) {
                log.warn("synthesis stream interrupted: {}", e.toString());
            }
            if (cancelled(sink)) return;

            // 行内引用规整：模型不可信，越界编号剔除、缺失编号补标
            finalAnswer = SynthesisPrompt.normalizeMarkers(answer.toString(), citations.size());
            // 引用卡片只发一次：卡片由同一批检索片段生成，重试用的是同批片段，编号与文档都没变，
            // 重复下发只会让验收脚本数出双份卡片
            if (attempt == 1 && !citations.isEmpty()) sink.next(sse("citation", citations));
            step(sink, "synthesis", "done",
                    String.format("基于 %d 个片段合成，含 %d 处行内引用%s%s",
                            SynthesisPrompt.limited(hits).size(), citations.size(), ctxNote,
                            attempt > 1 ? "（第 " + attempt + " 次生成）" : ""));
            if (cancelled(sink)) return;

            step(sink, "validation", "running", null);
            verdict = judge.faithfulness(question, hits, finalAnswer);
            qualityPassed = !citations.isEmpty()
                    && (verdict.judged() ? verdict.score() >= 60 : finalAnswer.contains("[1]"));
            boolean willRetry = !qualityPassed && attempt < maxAttempts;
            step(sink, "validation", qualityPassed ? "done" : "failed",
                    (verdict.judged() ? verdict.reason() + (qualityPassed ? "，通过" : "，未达阈值")
                            : verdict.reason() + (qualityPassed ? "；引用完整性抽检通过" : "；引用不足"))
                            + (willRetry ? "，正在重生成" : ""));
            if (!willRetry) break;
            retryNote = SynthesisPrompt.retryDirective(verdict.reason());
        }
        // 客户端在第一次生成就断开（attempt=0）或被中途打断：没有答案，不能往会话里写一条空消息
        if (attempt == 0 || cancelled(sink)) return;

        persistMessages(conv, question, finalAnswer, citations);
        // 回填用查缓存时拿到的同一个代号：期间若有人改库/改可见范围，代号已前移，这次写入自然读不到。
        // 未通过校验的答案不回填（D-1）：命中缓存会整段跳过检索与合成，把低质答案缓存回去等于把它的质量重试也跳过。
        if (qualityPassed) {
            cache.put(question, scopes, ctx.histKey(), gen, new ChatCache.Cached(conv.id, finalAnswer, citations, qualityPassed,
                    llm.chatModel(), System.currentTimeMillis())).subscribe();
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("qualityPassed", qualityPassed);
        done.put("attempts", attempt);
        done.put("conversationId", conv.id);
        done.put("messageId", messageId);
        done.put("model", llm.chatModel());
        done.put("provider", llm.real() ? "openai-compatible" : "mock");
        done.put("vectors", retrieval.vectorMode());
        done.put("llmAvailable", availability.llm);
        sink.next(sse("done", done));
    }

    /** 检索零命中拒答：不调用生成模型，固定文案如实告知，避免无依据回答 */
    private static final String NO_HIT_ANSWER =
            "未检索到与该问题相关的资料，为避免误导，本次不生成回答。\n\n"
                    + "您可以尝试：\n"
                    + "1) 更换关键词或调整提问方式；\n"
                    + "2) 确认相关文档已上传并在文档管理中显示「已入库」；\n"
                    + "3) 检查所选知识库范围是否正确。";

    /** 零命中时走这条：不调模型，直接把 NO_HIT_ANSWER 当 token 下发并落库，done 里带 noHit=true */
    private void refuseNoHit(FluxSink<ServerSentEvent<String>> sink, Conversation conv,
                             String question, String messageId) {
        step(sink, "synthesis", "running", "检索零命中，不调用模型作答（防幻觉拒答）");
        sink.next(sse("token", Map.of("text", NO_HIT_ANSWER)));
        step(sink, "synthesis", "done", "检索零命中，已拒答");
        step(sink, "validation", "failed", "检索零命中，未生成答案，qualityPassed=false");
        persistMessages(conv, question, NO_HIT_ANSWER, List.of());
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("qualityPassed", false);
        done.put("conversationId", conv.id);
        done.put("messageId", messageId);
        done.put("noHit", true);
        sink.next(sse("done", done));
    }

    // ==================== 会话与消息 ====================

    /**
     * 会话读取/创建：MySQL 直读（D-30 后 conversations 没有内存镜像，每轮问答一次按 id 取）。
     * 新会话只在本次请求的对象里存在，要到 {@link #persistMessages} 才落库——
     * 原先"先塞内存镜像、回答生成完再写 MySQL"的中间态没有读者，被删掉后行为不变。
     */
    private Conversation resolveConversation(User user, ChatRequest req, String question, Scope scope) {
        List<String> scopes = scope.scopes();
        if (req.conversationId() != null && !req.conversationId().isBlank()) {
            Conversation existing = db.findConversation(req.conversationId()).orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.userId, user.id)) throw BizException.forbidden("无权访问该会话");
                return existing;
            }
        }
        Conversation conv = new Conversation();
        conv.userId = user.id;
        conv.title = question.length() > 24 ? question.substring(0, 24) + "…" : question;
        conv.knowledgeBaseIds = new ArrayList<>(scopes);
        return conv;
    }

    /** 把这一问一答追加到会话对象并整体存库（新会话此时才真正落库）；写库失败只告警，不影响已下发的回答 */
    private void persistMessages(Conversation conv, String question, String answer,
                                 List<Map<String, Object>> citations) {
        ChatMessage userMsg = new ChatMessage("user", question);
        ChatMessage aiMsg = new ChatMessage("assistant", answer);
        aiMsg.citations = citations == null ? List.of() : citations;
        conv.messages.add(userMsg);
        conv.messages.add(aiMsg);
        conv.updatedAt = System.currentTimeMillis();
        try {
            db.saveConversation(conv);
        } catch (Exception e) {
            log.warn("persist conversation failed: {}", e.toString());
        }
    }

    /** 检索片段转引用卡片，转发 SynthesisPrompt.citations；主链路在 pipeline 内直接调静态方法，此入口留给包内其他调用 */
    List<Map<String, Object>> citationsOf(List<ChunkHit> hits) {
        return SynthesisPrompt.citations(hits);
    }

    // ==================== 会话管理接口（Controller 侧包 boundedElastic） ====================

    /** 该用户全部会话的摘要行（id/标题/updatedAt/知识库范围），直读 MySQL，阻塞调用由 Controller 侧包 boundedElastic */
    public List<Map<String, Object>> conversationsOf(User user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conversation c : db.conversationsOf(user.id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("title", c.title);
            m.put("updatedAt", c.updatedAt);
            m.put("knowledgeBaseIds", c.knowledgeBaseIds);
            out.add(m);
        }
        return out;
    }

    /** 单个会话的逐条消息（含引用与时间戳）；会话不存在 404，非本人 403 */
    public List<Map<String, Object>> messagesOf(User user, String convId) {
        Conversation c = db.findConversation(convId)
                .orElseThrow(() -> BizException.notFound("会话不存在"));
        if (!Objects.equals(c.userId, user.id)) throw BizException.forbidden("无权访问该会话");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : c.messages) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id);
            mm.put("role", m.role);
            mm.put("content", m.content);
            mm.put("citations", m.citations == null ? List.of() : m.citations);
            mm.put("createdAt", m.createdAt);
            out.add(mm);
        }
        return out;
    }

    /** 删除会话：先按 id 取库校验存在与归属（404/403），才调 db.deleteConversation */
    public void deleteConversation(User user, String convId) {
        Conversation c = db.findConversation(convId)
                .orElseThrow(() -> BizException.notFound("会话不存在"));
        if (!Objects.equals(c.userId, user.id)) throw BizException.forbidden("无权访问该会话");
        db.deleteConversation(convId);
    }

    /**
     * 本次提问的知识库范围。求交逻辑只有 Visibility.scopeFor 一处（D-34）；
     * dropped 为请求中被剔除的不可见/已删除库 id，必须如实告知用户，
     * 不能让他们以为限定了范围、实际却在全库检索（D-9）。
     */
    private Scope scopeOf(User user, List<String> requested) {
        List<String> scopes = visibility.scopeFor(user, requested);
        List<String> dropped = new ArrayList<>();
        if (requested != null) {
            for (String id : requested) {
                if (!scopes.contains(id) && !dropped.contains(id)) dropped.add(id);
            }
        }
        return new Scope(scopes, dropped);
    }

    /** 实际检索范围 + 被剔除的库 id + 可直接下发给用户的提示文案 */
    private record Scope(List<String> scopes, List<String> dropped) {
        /** 有剔除时给出可直发用户的提示，无剔除返回 null（调用方据此决定是否拼进检索阶段文案） */
        String note() {
            if (dropped.isEmpty()) return null;
            return "注意：所选 " + dropped.size() + " 个知识库不可见或已删除，已从本次范围中剔除；"
                    + (scopes.isEmpty() ? "当前没有可检索的知识库。"
                    : "实际只在剩余 " + scopes.size() + " 个可见知识库中检索。");
        }
    }

    // ==================== SSE helpers ====================

    /** 下发一条 agent-update 进度事件：step=阶段名，status=running/done/failed/skipped 之一 */
    private void step(FluxSink<ServerSentEvent<String>> sink, String stage, String status, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", stage);
        data.put("status", status);
        if (detail != null) data.put("detail", detail);
        sink.next(sse("agent-update", data));
    }

    /** 对象先 JSON 序列化再包成带事件名的 ServerSentEvent，前端按事件名分发 */
    private ServerSentEvent<String> sse(String event, Object data) {
        return ServerSentEvent.<String>builder(Json.write(data)).event(event).build();
    }

    private boolean cancelled(FluxSink<?> sink) {
        return sink.isCancelled();
    }
}
