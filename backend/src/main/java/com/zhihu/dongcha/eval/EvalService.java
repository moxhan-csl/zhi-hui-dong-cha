package com.zhihu.dongcha.eval;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.llm.FallbackLlmProvider;
import com.zhihu.dongcha.llm.HashVectorRejectedException;
import com.zhihu.dongcha.llm.JudgeService;
import com.zhihu.dongcha.llm.SynthesisPrompt;
import com.zhihu.dongcha.model.EvalRun;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.rag.ChunkHit;
import com.zhihu.dongcha.rag.RetrievalService;
import com.zhihu.dongcha.eval.GoldenQuestionService.Golden;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 评测中心：对题库（{@link GoldenQuestionService}，MySQL）里<b>已人工复核且启用</b>的题目跑真实链路评测。
 * <ul>
 *   <li>检索：走问答同一条<b>严格</b>路径（{@code retrieveForChat}，D-28）。原先走的容错路径会在
 *       embedding 故障时静默退回哈希向量，那种窗口跑出来的分数测的是假检索；现在拿不到真向量就
 *       显式记失败，全部样本都失败则整轮判 failed、不出分；</li>
 *   <li>可见范围：<b>发起人</b>的可见库（原先固定取第一个 SYS_ADMIN，等于任何人点发起都按全站最大权限评测，
 *       与问答口径不是同一个测量）；</li>
 *   <li>答案：与线上问答同一条流水线（pgvector 检索 + 配置的 chat 模型流式合成）；</li>
 *   <li>检索准确率 / 引用完整率：规则计算（期望文档是否出现在 TopK、行内引用是否可溯源）；</li>
 *   <li>相关度 / 忠实度 / 幻觉率：LLM-as-Judge（模型同 {@code app.llm.chat-model}），固定线程池并发 ≤ {@code app.llm.judge-concurrency}；
 *       模型不可用或输出不可解析时该样本按规则口径展示并在明细标注 rule-fallback，
 *       但<b>不进汇总</b>（汇总只统计模型判定成功的样本，一条都没有时报 -1 未判定）；</li>
 *   <li>结果写穿 MySQL（eval_runs），并记录本次用的题目集合指纹；</li>
 *   <li>{@code app.eval.sample-limit} 可临时截断样本数控制耗时/成本。</li>
 * </ul>
 * 题库曾是 jar 里的 golden_set.jsonl：那意味着换考卷要发版，且答案键和被测语料同源，
 * 高分只能自证清白。现在题目一律来自数据库，未复核的题目不参与计分。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final DbStore db;
    private final RetrievalService retrieval;
    private final FallbackLlmProvider llm;
    private final JudgeService judge;
    private final GoldenQuestionService questions;
    private final int sampleLimit;
    private final int judgeConcurrency;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean evalRunning = false;

    public EvalService(DbStore db, RetrievalService retrieval, FallbackLlmProvider llm, JudgeService judge,
                       GoldenQuestionService questions,
                       @Value("${app.eval.sample-limit:0}") int sampleLimit,
                       @Value("${app.llm.judge-concurrency:4}") int judgeConcurrency) {
        this.db = db;
        this.retrieval = retrieval;
        this.llm = llm;
        this.judge = judge;
        this.questions = questions;
        this.sampleLimit = sampleLimit;
        this.judgeConcurrency = Math.max(1, Math.min(judgeConcurrency, 4));
    }

    /** 题库实况 + 本次会生效几条，前端展示用（条数一律来自数据库，不再写死） */
    public Map<String, Object> goldenSetInfo() {
        Map<String, Object> m = new LinkedHashMap<>(questions.stats());
        int total = ((Number) m.get("usable")).intValue();
        m.put("effectiveSampleCount", sampleLimit > 0 ? Math.min(sampleLimit, total) : total);
        m.put("sampleLimit", sampleLimit > 0 ? sampleLimit : null);
        return m;
    }

    /**
     * 启动一次异步评测。
     *
     * @param initiator   发起人：检索按**其可见知识库**执行，与线上问答同一口径（D-28）。
     *                    副作用是明确的——KM_ADMIN 发起时机密库里的期望文档必然判为未命中，
     *                    要覆盖机密库就得由 SYS_ADMIN 发起，这个取舍随 run 一起快照下来。
     * @param questionIds 勾选的题目；空 = 全部已复核且启用的题目
     */
    public String run(User initiator, List<String> questionIds) {
        if (evalRunning) throw new BizException(HttpStatus.CONFLICT, "EVAL_RUNNING", "已有评测任务进行中，请稍后再试");
        List<Golden> picked = questions.pickForRun(questionIds);
        if (picked.isEmpty()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "GOLDEN_EMPTY",
            "题库里没有「已复核且启用」的题目，无法评测。请先在题库页录入并复核考题——"
                            + "没有已复核的考卷时跑出来的分数没有测量含义，不如不出分");
        }
        if (sampleLimit > 0 && picked.size() > sampleLimit) {
            picked = picked.subList(0, sampleLimit);
        }
        EvalRun run = new EvalRun();
        run.metrics.put("progress", 0.0);
        run.questionIds.addAll(picked.stream().map(Golden::id).toList());
        run.questionSignature = GoldenQuestionService.signature(picked);
        run.env.putAll(envSnapshot(initiator, picked.size()));
        db.saveEvalRun(run);
        evalRunning = true;
        final List<Golden> samples = picked;
        executor.submit(() -> {
            try {
                execute(run, samples, initiator);
            } catch (Exception e) {
                log.warn("eval run failed", e);
                run.failReason = rootMessage(e);
                run.status = "failed";
            } finally {
                evalRunning = false;
                try {
                    db.saveEvalRun(run);
                } catch (Exception e) {
                    log.warn("persist eval run {} failed: {}", run.id, e.toString());
                }
            }
        });
        return run.id;
    }

    /** 评测主体（eval-worker 线程）：逐题严格检索+合成，判定模型并发提交；汇总时主观三项只算模型判定成功的样本，检索准确率与引用完整率分母为全部样本。 */
    private void execute(EvalRun run, List<Golden> samples, User viewer) {
        int n = samples.size();

        ExecutorService judges = Executors.newFixedThreadPool(judgeConcurrency, r -> {
            Thread t = new Thread(r, "eval-judge");
            t.setDaemon(true);
            return t;
        });
        List<Future<JudgeService.Judge3>> judgeFutures = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int retrievalFailures = 0;

        try {
            for (int i = 0; i < n; i++) {
                Golden g = samples.get(i);
                List<ChunkHit> hits;
                boolean retrievalFailed = false;
                try {
                    // 严格路径（D-28）：与线上问答同一条向量化+检索链，绝不拿哈希假向量算分
                    hits = retrieval.retrieveForChat(viewer, g.question(), null, attempt -> { });
                } catch (RetrievalService.RetrievalFailedException e) {
                    if (e.getCause() instanceof HashVectorRejectedException) throw e;
                    retrievalFailed = true;
                    retrievalFailures++;
                    hits = List.of();
                }
                String answer = retrievalFailed ? "" : synthesize(g.question(), hits);
                List<Map<String, Object>> citations = SynthesisPrompt.citations(hits);
                String normalized = citations.isEmpty() ? answer : SynthesisPrompt.normalizeMarkers(answer, citations.size());

                boolean hit = hits.stream().anyMatch(h -> h.chunk().docName.contains(g.expectedDoc())
                        || g.expectedDoc().contains(h.chunk().docName));
                int markers = distinctMarkers(normalized, citations.size());
                double citationScore = citationCompleteness(markers, citations.size());

                pending.add(new Pending(g, normalized, hits, hit, markers, citations, citationScore, retrievalFailed));
                if (retrievalFailed) {
                    // 空答案送去做 LLM 判定只是白烧一次调用，相关度也不会可信——占位 null，汇总里按未判定处理
                    judgeFutures.add(null);
                } else {
                    final String q = g.question(), golden = g.golden(), ans = normalized;
                    final List<ChunkHit> h = hits;
                    judgeFutures.add(judges.submit(() -> judge.judgeSample(q, golden, ans, h)));
                }

                run.metrics.put("progress", round1((i + 1) * 100.0 / n));
                if ((i + 1) % 5 == 0 || i + 1 == n) persistQuietly(run);
            }

            if (retrievalFailures == n) {
                // 一题都没检出来：这一轮没有任何测量含义，不出分，直接把原因写进记录
                run.metrics.put("retrievalFailedCount", (double) retrievalFailures);
                run.failReason = "全部 " + n + " 题的检索链路失败（embedding 或向量库不可用），本轮不出分";
                run.status = "failed";
                return;
            }

            double sumRelevance = 0, sumFaithfulness = 0, sumHallucination = 0, sumCitation = 0;
            int retrievalHits = 0, judged = 0;
            for (int i = 0; i < pending.size(); i++) {
                Pending p = pending.get(i);
                Future<JudgeService.Judge3> f = judgeFutures.get(i);
                JudgeService.Judge3 j = f == null ? null : await(f);
                double relevance, faithfulness, hallucination;
                boolean ruleFallback = (j == null);
                if (!ruleFallback) {
                    relevance = j.relevance();
                    faithfulness = j.faithfulness();
                    hallucination = j.hallucinationRate();
                    judged++;
                    // 只有模型判定成功的样本进入主观三项汇总
                    sumRelevance += relevance;
                    sumFaithfulness += faithfulness;
                    sumHallucination += hallucination;
                } else {
                    // Judge 不可用：样本明细退回规则口径并明确标注，但不参与汇总——
                    // 规则分是常量（85/40、88/50），与模型分混成平均分后就是一支没有测量含义的曲线
                    relevance = ruleRelevance(p);
                    faithfulness = ruleFaithfulness(p);
                    hallucination = p.hit ? 0 : 100;
                }
                if (p.hit) retrievalHits++;
                sumCitation += p.citationScore;

                Map<String, Object> scores = new LinkedHashMap<>();
                scores.put("retrievalPrecision", p.hit ? 100.0 : 0.0);
                scores.put("relevance", round1(relevance));
                scores.put("faithfulness", round1(faithfulness));
                scores.put("hallucinationRate", round1(hallucination));
                scores.put("citationCompleteness", round1(p.citationScore));

                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("index", i + 1);
                sample.put("questionId", p.golden.id());
                sample.put("question", p.golden.question());
                sample.put("golden", p.golden.golden());
                sample.put("answer", clip(p.answer, 300));
                sample.put("expectedDoc", p.golden.expectedDoc());
                sample.put("retrievedDocs", p.hits.stream().limit(4).map(h -> h.chunk().docName).toList());
                sample.put("scores", scores);
                sample.put("retrievalFailed", p.retrievalFailed);
                sample.put("judge", p.retrievalFailed ? "retrieval-failed" : (ruleFallback ? "rule-fallback" : llm.chatModel()));
                // 未判定的样本不算通过：把规则常量满足阈值当成"合格"是自评打分
                sample.put("passed", p.hit && p.markers > 0 && !ruleFallback
                        && relevance >= 85 && faithfulness >= 88 && hallucination <= 5);
                run.samples.add(sample);
            }

            int m = Math.max(1, run.samples.size());
            Map<String, Double> mm = new LinkedHashMap<>();
            mm.put("retrievalPrecision", round1(retrievalHits * 100.0 / m));
            // -1 = 本次没有任何样本被模型判定，前端按"未判定"展示，不得画成 0 分
            mm.put("relevance", judged > 0 ? round1(sumRelevance / judged) : -1.0);
            mm.put("faithfulness", judged > 0 ? round1(sumFaithfulness / judged) : -1.0);
            mm.put("hallucinationRate", judged > 0 ? round1(sumHallucination / judged) : -1.0);
            mm.put("citationCompleteness", round1(sumCitation / m));
            mm.put("sampleCount", (double) m);
            mm.put("judgedByLlm", (double) judged);
            // 检索失败的样本仍留在分母里拖低分数，同时单独计数：只降分不计数，事后看不出是能力差还是链路断
            mm.put("retrievalFailedCount", (double) retrievalFailures);
            mm.put("progress", 100.0);
            run.metrics = new ConcurrentHashMap<>(mm);
            run.status = "done";
            log.info("eval run {} done (judge={} / {}) metrics={}", run.id, judged, m, mm);
        } finally {
            judges.shutdownNow();
        }
    }

    /**
     * 与线上问答同一条合成链路；评测题目是独立问题，因此不带会话历史（D-11），
     * 也**不带质量重试**（D-1）：重试会改变 faithfulness 的口径，加了它新旧运行更不可比。
     */
    private String synthesize(String question, List<ChunkHit> hits) {
        StringBuilder sb = new StringBuilder();
        try {
            llm.streamSynthesis(question, hits, List.of(), null, db.config().models.chat.temperature)
                    .toStream()
                    .forEach(c -> {
                        if (c.text() != null) sb.append(c.text());
                    });
        } catch (RuntimeException e) {
            log.warn("eval synthesis failed for question={}: {}", question, e.toString());
        }
        return sb.toString();
    }

    // ==================== 规则口径 ====================
    /** 行内引用编号去重计数（越界编号已被 SynthesisPrompt 剔除） */
    private int distinctMarkers(String answer, int max) {
        Set<Integer> found = new HashSet<>();
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\\[(\\d{1,3})]").matcher(answer == null ? "" : answer);
        while (mt.find()) {
            int v = Integer.parseInt(mt.group(1));
            if (v >= 1 && v <= max) found.add(v);
        }
        return found.size();
    }

    /**
     * 引用完整率（规则）：答案中出现的行内引用编号去重数 / 系统给出的引用卡片数。
     * 只管"给出的卡片是否都被标出来"；检索是否命中期望文档由 retrievalPrecision 负责，
     * 不在这里做"未命中就打了对折"的二次折算——那会让指标解释不清。
     */
    private double citationCompleteness(int markers, int citations) {
        if (citations == 0 || markers == 0) return 0;
        return Math.min(100.0, markers * 100.0 / citations);
    }

    /** 模型判定失败时的兜底常量（命中期望文档 85 / 未命中 40），只进样本明细展示，不进汇总。 */
    private double ruleRelevance(Pending p) {
        return p.hit ? 85 : 40;
    }

    /** 模型判定失败时的兜底常量（无检索结果 0 / 有行内引用标记 88 / 其余 50），只进明细不进汇总。 */
    private double ruleFaithfulness(Pending p) {
        if (p.hits.isEmpty()) return 0;
        return p.markers > 0 ? 88 : 50;
    }

    /** 等待单个判定模型结果，上限 180 秒；超时或异常返回 null，该样本按未判定（rule-fallback）处理。 */
    private JudgeService.Judge3 await(Future<JudgeService.Judge3> f) {
        try {
            return f.get(180, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("judge task failed: {}", e.toString());
            return null;
        }
    }

    /** 运行中增量落库（进度快照），吞掉存储异常以免 DB 抖动中断整轮评测。 */
    private void persistQuietly(EvalRun run) {
        try {
            db.saveEvalRun(run);
        } catch (Exception e) {
            log.warn("persist eval run failed: {}", e.toString());
        }
    }

    /** 单题在等判定模型返回前攒下的规则侧中间结果：题面、检索命中、引用标记数与检索失败标记。 */
    private static final class Pending {
        final Golden golden;
        final String answer;
        final List<ChunkHit> hits;
        final boolean hit;
        final int markers;
        final List<Map<String, Object>> citations;
        final double citationScore;
        final boolean retrievalFailed;

        Pending(Golden golden, String answer, List<ChunkHit> hits, boolean hit, int markers,
                List<Map<String, Object>> citations, double citationScore, boolean retrievalFailed) {
            this.golden = golden;
            this.answer = answer;
            this.hits = hits;
            this.hit = hit;
            this.markers = markers;
            this.citations = citations;
            this.citationScore = citationScore;
            this.retrievalFailed = retrievalFailed;
        }
    }

    /**
     * 运行前快照检索环境（D-28）：分数是否可比，除了题集指纹还取决于这三件事——
     * 用谁的可见范围、落在 pgvector 还是进程内 memory、向量化是真 embedding 还是哈希兜底。
     */
    private Map<String, Object> envSnapshot(User viewer, int sampleCount) {
        var rag = db.config().rag;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("initiator", viewer.account != null ? viewer.account : viewer.id);
        m.put("initiatorRole", viewer.role);
        m.put("vectorStore", retrieval.vectorMode());
        m.put("embedMode", retrieval.embedMode());
        m.put("chatModel", llm.chatModel());
        m.put("ragTopK", rag.topK);
        m.put("ragScoreThreshold", rag.scoreThreshold);
        m.put("ragQueryRewrite", rag.queryRewrite);
        m.put("judgeConcurrency", judgeConcurrency);
        m.put("sampleCountRequested", sampleCount);
        m.put("degraded", "memory".equals(retrieval.vectorMode()) || "hash-fallback".equals(retrieval.embedMode()));
        return m;
    }

    /** 失败原因取最内层消息：外层包装往往是"重试 N 次仍失败"，真正的修法在最里面 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    // ==================== 查询（MySQL 直读） ====================

    /** 全部历史运行的概览（view(false)：指标+指纹+env，不展开样本明细），按时间倒序。 */
    public List<Map<String, Object>> runs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalRun r : db.evalRunsDesc()) out.add(r.view(false));
        return out;
    }

    /** 单次运行完整视图（view(true)，含每题答案与分项得分），ID 不存在抛 404。 */
    public Map<String, Object> runDetail(String id) {
        return db.findEvalRun(id).map(r -> r.view(true)).orElseThrow(() -> BizException.notFound("评测记录不存在"));
    }

    /** 趋势点列表：只取 done 的运行、按时间正序，每点带指标、题集指纹与环境快照（D-30 头部投影）。 */
    public List<Map<String, Object>> trend() {
        // 头部投影（D-30）：趋势只用 metrics/env，不需要每行的样本明细
        List<DbStore.RunHead> list = new ArrayList<>(db.runHeadsDesc());
        Collections.reverse(list);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbStore.RunHead r : list) {
            if (!"done".equals(r.status)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", r.createdAt);
            m.put("metrics", r.metrics);
            // 题集指纹随点返回：前端据此判断两次运行是否真的可比
            m.put("questionSignature", r.questionSignature);
            m.put("questionCount", r.questionIds.size());
            // 检索环境快照同样要带：换了发起人或向量库退回 memory，同一套题的分数就不是同一个测量
            m.put("env", new LinkedHashMap<>(r.env));
            out.add(m);
        }
        return out;
    }

    /** 单次运行导出为 JSONL：第 1 行运行头（状态/指标/env），之后每行一个样本对象。 */
    public String exportJsonl(String id) {
        EvalRun r = db.findEvalRun(id).orElseThrow(() -> BizException.notFound("评测记录不存在"));
        StringBuilder sb = new StringBuilder();
        try {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("type", "run");
            head.put("id", r.id);
            head.put("createdAt", r.createdAt);
            head.put("status", r.status);
            head.put("metrics", r.metrics);
            head.put("env", r.env);
            sb.append(Json.MAPPER.writeValueAsString(head)).append("\n");
            for (Map<String, Object> s : r.samples) {
                sb.append(Json.MAPPER.writeValueAsString(Map.of("type", "sample", "data", s))).append("\n");
            }
        } catch (Exception e) {
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "导出失败");
        }
        return sb.toString();
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
