package com.zhihu.dongcha.rag;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 本机哈希向量嵌入（字符 bigram + ASCII 词 → 1024 维带符号桶计数，L2 归一化）：
 * 与 text-embedding-v3 <b>同维度</b>，所以混进真向量库不会报任何错——这正是 D-14 默认拒绝它的原因。
 * 现在只有两条合法出路：显式 {@code app.llm.allow-hash-vectors=true}（本机跑通链路，伴随 WARN），
 * 或 {@code MemoryChunkStore} 的余弦比较工具方法 {@link #cosine}。
 */
@Component
public class HashEmbedder {

    public static final int DIM = 1024;
    /** 高频虚词，剔除后显著提升中文检索区分度 */
    private static final String STOP = "的是有们这中大为上个国到说去也都很不以并且其或等之在就那很吗呢啊吧";

    /** 入桶后做 L2 归一化；单字桶只在整段长度 ≤6 时才加，ASCII 词要求至少 2 个字符 */
    public double[] embed(String text) {
        double[] v = new double[DIM];
        if (text == null) return v;        String s = text.toLowerCase(Locale.ROOT);
        // 中文字符 bigram
        for (int i = 0; i + 1 < s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1);
            if (isCjk(a) && isCjk(b) && !isStop(a) && !isStop(b)) {
                add(v, "" + a + b, 1.0);
            }
        }
        // 单字（仅短查询兜底，权重压低避免无关文本相关性抬升）
        for (int i = 0; i < s.length(); i++) {
            char a = s.charAt(i);
            if (isCjk(a) && !isStop(a) && s.length() <= 6) add(v, "c" + a, 0.4);
        }
        // ASCII 词
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c) && c < 128) {
                word.append(c);
            } else {
                if (word.length() >= 2) add(v, "w" + word, 1.5);
                word.setLength(0);
            }
        }
        // L2 归一化
        double norm = 0;
        for (double d : v) norm += d * d;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    /** 只做点积、不除模长：输入若未 L2 归一化，得到的就不是真正的余弦相似度 */
    public static double cosine(double[] a, double[] b) {
        double sum = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) sum += a[i] * b[i];
        return sum;
    }

    /** 同一 token 恒定落到同一个桶（哈希对 DIM 取模），符号由哈希第 41 位决定，权重带正负累加 */
    private void add(double[] v, String token, double weight) {
        long h = hash(token);
        int idx = (int) Math.floorMod(h, DIM);
        double sign = ((h >>> 40) & 1) == 0 ? 1 : -1;
        v[idx] += sign * weight;
    }

    private long hash(String s) {
        long h = 1125899906842597L;
        for (char c : s.toCharArray()) h = 31 * h + c;
        return h;
    }

    /** 只认 CJK 统一表意文字基本区（0x4E00-0x9FFF），扩展区汉字与假名都不会被当成中文 */
    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private boolean isStop(char c) {
        return STOP.indexOf(c) >= 0;
    }
}
