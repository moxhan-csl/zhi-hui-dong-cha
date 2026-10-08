package com.zhihu.dongcha.llm;

import com.zhihu.dongcha.rag.ChunkHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge：质量验证（忠实度）与评测三项主观指标打分。
 * 约定模型只回三个数字，便于稳定解析；模型不可用或解析失败时按"降级通过"处理，不阻塞主流程。
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);
    private static final Pattern NUM = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)");

    private final FallbackLlmProvider provider;

    /** 注入门面而非具体实现：provider.real() 已含降级位，mock 环境下这里一次模型都不会调 */
    public JudgeService(FallbackLlmProvider provider) {
        this.provider = provider;
    }

    /** ChatService 的质量门：judged 时看 score>=60，未判定退回抽检答案里有没有 [1]；reason 进 SSE 与重生成指令 */
    public record Verdict(double score, String reason, boolean judged) {
    }

    /** 回答忠实度（0-100）：judged=false 表示未真正调用模型（降级） */
    public Verdict faithfulness(String question, List<ChunkHit> hits, String answer) {
        if (!provider.real() || hits.isEmpty()) return new Verdict(-1, "未启用 LLM 判定或零命中，降级通过", false);
        String system = "你是严格的 RAG 质检员。只输出一个 0-100 的整数，表示【回答】对【资料】的忠实程度"
                + "（回答中的每个事实都能被资料支持得高分，出现资料外的编造信息扣重分）。不要输出其他内容。";
        StringBuilder user = new StringBuilder();
        user.append("【问题】").append(question).append('\n');
        user.append("【资料】\n").append(SynthesisPrompt.userPrompt(question, hits).replace("【问题】" + question, "").strip()).append('\n');
        user.append("【回答】").append(answer).append('\n');
        try {
            LlmResult r = provider.complete(system, user.toString(), 0.0, 20);
            Double v = firstNumber(r.text());
            if (v == null) return new Verdict(-1, "判定结果不可解析，降级通过: " + clip(r.text()), false);
            return new Verdict(v, provider.chatModel() + " 忠实度判定 " + v.intValue() + "/100", true);
        } catch (RuntimeException e) {
            log.warn("faithfulness judge failed: {}", e.toString());
            return new Verdict(-1, "LLM 判定失败，降级通过", false);
        }
    }

    /** EvalService 汇总的三个主观分；judgeSample 返回 null 时该样本退回规则常量分且不参与汇总 */
    public record Judge3(double relevance, double faithfulness, double hallucinationRate, boolean judged) {
    }

    /**
     * 评测主观三项：相关度 / 忠实度 / 幻觉率（%），一次调用返回三个数字以控制成本。
     * 未启用真实模型时返回 null，由 EvalService 走规则兜底。
     */
    public Judge3 judgeSample(String question, String golden, String answer, List<ChunkHit> hits) {
        if (!provider.real()) return null;
        String system = """
                你是企业知识库问答的评审专家。给定【问题】【参考答案】【待评回答】【检索资料】，
                请只输出一行三个数字，用竖线分隔：相关度|忠实度|幻觉率
                前两个为 0-100 整数（相关度=待评回答是否切题并覆盖参考答案要点；
                忠实度=待评回答的每个事实是否都能从检索资料找到支持）；
                幻觉率为 0-100 的数值（待评回答中编造内容占比）。不要输出任何解释、单位或多余字符。""";
        StringBuilder user = new StringBuilder();
        user.append("【问题】").append(question).append('\n');
        user.append("【参考答案】").append(golden).append('\n');
        user.append("【待评回答】").append(answer).append('\n');
        user.append("【检索资料】\n");
        int i = 1;
        for (ChunkHit h : SynthesisPrompt.limited(hits)) {
            user.append('[').append(i++).append("] ").append(SynthesisPrompt.trim(h.chunk().text)).append('\n');
        }
        if (hits.isEmpty()) user.append("（无检索结果）\n");
        try {
            LlmResult r = provider.complete(system, user.toString(), 0.0, 40);
            List<Double> nums = numbers(r.text());
            if (nums.size() < 3) {
                log.warn("judge 输出无法解析: {}", clip(r.text()));
                return null;
            }
            return new Judge3(clamp(nums.get(0)), clamp(nums.get(1)), clamp(nums.get(2)), true);
        } catch (RuntimeException e) {
            log.warn("judge call failed: {}", e.toString());
            return null;
        }
    }

    // ---------- helpers ----------

    /** 忠实度只要一个数：取首个匹配，解析不到返回 null，由调用方按"降级通过"处理 */
    private Double firstNumber(String text) {
        List<Double> n = numbers(text);
        return n.isEmpty() ? null : n.get(0);
    }

    /** 正则抓所有数字，不校验位置与个数——所以两份 prompt 都硬性要求模型"只输出数字" */
    private List<Double> numbers(String text) {
        List<Double> out = new java.util.ArrayList<>();
        if (text == null) return out;
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group(1)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 模型给出的越界数字（如把 100 写成 1000）夹回 0..100 再交给 EvalService 汇总 */
    private double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** 日志用的截断原文，避免整段模型输出刷进日志 */
    private String clip(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
