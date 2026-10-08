package com.zhihu.dongcha.llm;

import com.zhihu.dongcha.rag.ChunkHit;
import com.zhihu.dongcha.rag.HashEmbedder;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MockLlmProvider：真实 LLM 不可用（app.llm.provider=mock、启动探针失败或运行期故障）时的回退实现。
 * 生成：把检索片段按 [1][2] 行内引用模板拼装；嵌入：本机字符哈希向量（维度与真实模型一致 1024）。
 * 保证无外网环境下问答、入库、评测链路仍可跑通。
 */
public class MockLlmProvider implements LlmProvider {

    private final HashEmbedder hashEmbedder;

    /** LlmConfig 里独立成 Bean：即使 primary 是真实现，门面也持有同一个实例作为降级目标 */
    public MockLlmProvider(HashEmbedder hashEmbedder) {
        this.hashEmbedder = hashEmbedder;
    }

    /**
     * 模板拼装不具备理解能力：{@code history} 与 {@code retryNote} 都只接住不使用，
     * 输出仍然只由检索片段决定——同一片段必得同一串文字，所以问答链路不会在 mock 上重试（见 ChatService）。
     */
    @Override
    public Flux<LlmChunk> streamSynthesis(String question, List<ChunkHit> hits, List<Turn> history,
                                          String retryNote, double temperature) {
        String answer = generate(question, hits);
        List<LlmChunk> chunks = new ArrayList<>();
        for (String piece : split(answer)) chunks.add(LlmChunk.text(piece));
        chunks.add(LlmChunk.usage(new LlmUsage(Math.max(1, question.length()),
                Math.max(1, answer.length()), true)));
        return Flux.fromIterable(chunks);
    }

    /** 恒返回空串且不抛异常：门面在 mock 上不会二次降级，JudgeService 解析不出数字即按未判定通过 */
    @Override
    public LlmResult complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        // 回退实现不具备判定能力：调用方（质量验证 / 评测 Judge）依据 real() 走降级分支
        return new LlmResult("", new LlmUsage(0, 0, true));
    }

    /** 逐条走 HashEmbedder 得 1024 维向量，维度与真向量一致——这正是 D-14 要在入库前拦住它的原因 */
    @Override
    public List<double[]> embed(List<String> texts) {
        List<double[]> out = new ArrayList<>();
        for (String t : texts) out.add(hashEmbedder.embed(t == null ? "" : t));
        return out;
    }

    /** 与真实实现同一套关键词规则、同样不调模型，只是文案标注 mock 来源 */
    @Override
    public String intentDetail(String question) {
        if (question.matches(".*(流程|制度|规定|规范|申请|报销|审批|入职|考勤|采购).*"))
            return "识别为「制度流程咨询」意图（mock provider）";
        if (question.matches(".*(架构|部署|接口|版本|组件|模块|配置|运维).*"))
            return "识别为「产品技术问题」意图（mock provider）";
        if (question.matches(".*(战略|利润|成本|预算|财报|经营).*"))
            return "识别为「经营与财务」意图（mock provider）";
        return "识别为「通用知识问答」意图（mock provider）";
    }

    @Override
    public String chatModel() {
        return "mock";
    }

    /** 名字里带上维度：日志与向量来源标注中一眼可辨这是哈希假向量而非真模型 */
    @Override
    public String embeddingModel() {
        return "mock-hash-" + HashEmbedder.DIM;
    }

    @Override
    public boolean real() {
        return false;
    }

    // ==================== 模板拼装 ====================

    /** 模板拼装答案：零命中回固定的"未检索到"话术，命中时最多取 4 条片段各配一句并标 [1]..[4] */
    public String generate(String question, List<ChunkHit> hits) {
        if (hits.isEmpty()) {
            return "抱歉，在当前您有权访问的知识库中未检索到与「" + question + "」相关的内容。"
                    + "建议：1) 换用更具体的关键词或业务术语重新提问；2) 确认相关文档是否已完成解析入库；"
                    + "3) 若信息尚未沉淀，请联系知识管理员补充对应知识库。";
        }
        List<String> s = hits.stream().limit(SynthesisPrompt.MAX_CITATIONS)
                .map(h -> bestSentence(question, h.chunk().text)).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("根据企业知识库检索结果，关于「").append(question).append("」为您整理如下：\n\n");
        if (s.size() == 1) {
            sb.append(s.get(0)).append("[1]。\n");
        } else {
            sb.append(s.get(0)).append("[1]；").append(s.get(1)).append("[2]。\n");
            if (s.size() >= 3) {
                sb.append("此外，").append(s.get(2)).append("[3]");
                if (s.size() >= 4) sb.append("，同时").append(s.get(3)).append("[4]");
                sb.append("。\n");
            }
        }
        sb.append("\n综合以上来源，").append(summaryTail(hits)).append("如需更多细节，可点击文末引用卡片查看原文。");
        return sb.toString();
    }

    /** 取块内与问题词面重叠最高的句子（去 markdown 标题） */
    private String bestSentence(String question, String text) {
        String clean = (text == null ? "" : text).replaceAll("(?m)^#{1,6} .*$", "")
                .replaceAll("[\\r\\n]+", " ").strip();
        String[] parts = clean.split("(?<=[。；;])");
        String best = "";
        double bestScore = -1;
        for (String p : parts) {
            String cand = p.strip();
            if (cand.length() < 8) continue;
            double score = overlap(question, cand);
            if (score > bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        if (best.isEmpty()) best = clean;
        if (best.length() > 90) best = best.substring(0, 90);
        return best.replaceAll("[。；;\\s]+$", "");
    }

    /** 无分词的中文近似：按二元字组求重合，分母是问题的字组数，句子越贴合问题分越高 */
    private double overlap(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0;
        int hit = 0;
        Set<String> bg = new HashSet<>();
        for (int i = 0; i + 1 < a.length(); i++) bg.add(a.substring(i, i + 2));
        for (int i = 0; i + 1 < b.length(); i++) if (bg.contains(b.substring(i, i + 2))) hit++;
        return (double) hit / bg.size();
    }

    /** 收尾话术：把首条命中片段的文档名写成"主要依据"，并没有真的做多片段综合 */
    private String summaryTail(List<ChunkHit> hits) {
        return "以《" + hits.get(0).chunk().docName + "》为主要依据，";
    }

    /** 定长 10 字符切片，只为模拟流式逐块下发，与句子边界无关；模板文本固定所以分块也是确定的 */
    private List<String> split(String answer) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += 10) {
            out.add(answer.substring(i, Math.min(answer.length(), i + 10)));
        }
        return out;
    }
}
