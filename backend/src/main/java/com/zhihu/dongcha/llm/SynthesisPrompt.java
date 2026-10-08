package com.zhihu.dongcha.llm;

import com.zhihu.dongcha.model.ChatMessage;
import com.zhihu.dongcha.rag.ChunkHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合成阶段的 prompt 与引用对齐：
 * 片段按检索相关度顺序编号为 [1..N]，citation 列表用同一顺序，保证行内 [n] 与卡片一一对应。
 * 模型不可信，因此 citation 一律由后端检索结果生成，并对模型输出的编号做规整（越界剔除、缺失补标）。
 */
public final class SynthesisPrompt {

    /** 注入 prompt 的片段上限，也是 citation 上限 */
    public static final int MAX_CITATIONS = 4;
    /** 单条片段写进 prompt 的字数上限，trim() 的截断口径 */
    public static final int SNIPPET_CHARS = 700;

    /** 行内引用标记 [n]：规整本轮答案与清洗历史里的旧编号共用这一条判据 */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

    private SynthesisPrompt() {
    }

    /** 编号规则与后端判据是绑定的：只准用【资料】里出现过的 [n]，越界的由 normalizeMarkers 剔除、全缺的补一个 [1] */
    public static String systemPrompt() {
        return """
                你是企业知识库智能助手。严格遵守：
                1) 只依据【资料】回答，禁止编造资料中不存在的事实、数字与来源；
                2) 之前的对话只用于理解指代（"它""上面那条"），不是事实来源；本轮结论仍须落在【资料】上；
                3) 使用简体中文，条理清晰、结论先行，控制在 500 字以内；
                4) 输出纯文本，不要用 Markdown 语法：不出现 # 标题、* 或 - 开头的列表、**加粗**、` 反引号、> 引用、| 表格；
                   需要分点时用"1. 2. 3."或"其一/其二"写成普通段落行，需要强调时直接把话写清楚；
                5) 在引用了某条资料的句子末尾标注其编号，形如 [1]、[2]，编号只能使用【资料】中给出过的编号；
                6) 若资料不足以回答，直接说明未检索到相关内容，不要臆测。""";
    }

    /** 资料在前、问题在后；[n] 编号与 citations() 同源（都走 limited()），只改一边就会错位 */
    public static String userPrompt(String question, List<ChunkHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【资料】\n");
        int i = 1;
        for (ChunkHit h : limited(hits)) {
            sb.append('[').append(i++).append("] 《").append(h.chunk().docName);
            // PDF 抽取带真实页边界（page≥1）；其余格式没有页码概念，page=0，绝不向模型谎报"第 N 页"
            if (h.chunk().page > 0) sb.append("》 第").append(h.chunk().page).append("页：");
            else sb.append("》：");
            sb.append(trim(h.chunk().text)).append('\n');
        }
        // JudgeService.faithfulness 靠 replace("【问题】"+question) 摘掉这一行，改文案或挪位置会让它摘不干净
        sb.append("\n【问题】").append(question).append('\n');
        return sb.toString();
    }

    /**
     * 低质重生成时追加给模型的指令（D-1）。只补"上一版哪里不合格、这一版要怎么做"，
     * <b>不改【资料】也不改【问题】</b>——同一批片段、同一个问题，变的只是这条要求；
     * 不追加任何新指令的话，同 prompt 同温度重跑拿到的基本是同一段文字，重试就成了白烧钱。
     */
    public static String retryDirective(String reason) {
        return "\n【重生成要求】上一版回答未通过质量校验（" + trim(reason) + "）。"
                + "请重新作答：只写【资料】里有的事实，删掉资料找不到依据的说法，"
                + "每个结论句末都带 [n] 行内编号，编号只能用【资料】中给出过的编号，并换一种组织方式。";
    }

    /**
     * 会话历史 → 模型上下文：取最近 {@code turns} 轮（1 轮 = 一问一答两条消息）。
     * 当前问题不在其中——它此刻还没落库。
     * <p>assistant 历史里的行内 [n] 会被剔除：那些编号指向上一轮的资料，与本轮【资料】无关，
     * 留着等于让模型把旧编号抄进新答案，引用卡片就会指向错的文档。
     */
    public static List<LlmProvider.Turn> historyOf(List<ChatMessage> messages, int turns) {
        if (messages == null || messages.isEmpty() || turns <= 0) return List.of();
        int size = messages.size();
        List<LlmProvider.Turn> out = new ArrayList<>();
        for (int i = Math.max(0, size - turns * 2); i < size; i++) {
            ChatMessage m = messages.get(i);
            boolean fromUser = "user".equals(m.role);
            if (!fromUser && !"assistant".equals(m.role)) continue;
            String content = trim(m.content);
            if (!fromUser) content = MARKER.matcher(content).replaceAll("").strip();
            if (content.isEmpty()) continue;
            out.add(new LlmProvider.Turn(fromUser ? "user" : "assistant", content));
        }
        return out;
    }

    /** citation 卡片（顺序与 prompt 编号一致），由后端检索结果生成 */
    public static List<Map<String, Object>> citations(List<ChunkHit> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 1;
        for (ChunkHit h : limited(hits)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", i++);
            c.put("doc", h.chunk().docName);
            c.put("docId", h.chunk().docId);
            c.put("page", h.chunk().page > 0 ? h.chunk().page : null);
            c.put("chunkIndex", h.chunk().index);
            String snippet = h.chunk().text == null ? "" : h.chunk().text;
            c.put("snippet", snippet.length() > 120 ? snippet.substring(0, 120) + "…" : snippet);
            c.put("score", Math.round(h.score() * 1000.0) / 1000.0);
            out.add(c);
        }
        return out;
    }

    /**
     * 规整模型输出的行内引用：
     * - 剔除越界编号（不在 1..max 内）
     * - 与上一个**被保留下来**的编号相同即丢弃（不看中间隔了多少句，所以"[1]…[2]…[1]"里的第三个 [1] 仍会保留）
     * - 若完全没有标注，则按首段补一个 [1]，保证引用可溯源
     */
    public static String normalizeMarkers(String answer, int max) {
        if (answer == null || answer.isEmpty()) return answer;
        // max=0 说明本轮没有任何资料卡片：所有编号都无源可溯，整批剔除
        if (max <= 0) return MARKER.matcher(answer).replaceAll("");
        Matcher m = MARKER.matcher(answer);
        StringBuilder sb = new StringBuilder();
        int lastKept = -1;
        boolean anyKept = false;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String replacement;
            if (n < 1 || n > max) {
                replacement = "";
            } else if (n == lastKept) {
                // lastKept 只在保留时更新：与上一个保留下来的编号重复就丢，不论中间隔了多少句
                replacement = "";
            } else {
                replacement = "[" + n + "]";
                lastKept = n;
                anyKept = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String out = sb.toString().replaceAll("\\.{2,}", "。").replaceAll("[ \\t]+\\n", "\n").strip();
        if (!anyKept) out = appendFirstParagraphMarker(out);
        return out;
    }

    /** 全篇无编号时的兜底：往第一段末尾标点前插 [1] 指向首条资料（max<=0 已在上一步整批剔除，不会走到这里） */
    private static String appendFirstParagraphMarker(String out) {
        int cut = out.indexOf("\n\n");
        String head = cut > 0 ? out.substring(0, cut) : out;
        String tail = cut > 0 ? out.substring(cut) : "";
        String trimmed = head.stripTrailing();
        if (trimmed.endsWith("。") || trimmed.endsWith(".") || trimmed.endsWith("！") || trimmed.endsWith("?")
                || trimmed.endsWith("？")) {
            head = trimmed.substring(0, trimmed.length() - 1) + "[1]" + trimmed.charAt(trimmed.length() - 1);
        } else {
            head = trimmed + "[1]。";
        }
        return head + tail;
    }

    /** prompt 的【资料】、citation 卡片与 Judge 的资料段都过这里截断，三处 [n] 编号才对得上 */
    public static List<ChunkHit> limited(List<ChunkHit> hits) {
        return hits.size() <= MAX_CITATIONS ? hits : hits.subList(0, MAX_CITATIONS);
    }

    /** 折叠所有空白再按 SNIPPET_CHARS 截断：片段带换行会打乱 prompt 的分条结构 */
    public static String trim(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() > SNIPPET_CHARS ? t.substring(0, SNIPPET_CHARS) + "…" : t;
    }
}
