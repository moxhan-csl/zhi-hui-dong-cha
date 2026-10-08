package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.llm.HashVectorRejectedException;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.store.Store;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * 检索规划与执行：query 改写（多路）→ 向量化（真实 embedding 服务）→
 * pgvector 近邻检索（故障降级内存余弦）→ 可见 kb 过滤 + score 阈值 + topK。
 * topK / scoreThreshold / queryRewrite 受 /settings/rag 热配置控制（MySQL settings_rag 表）。
 * <p>全站只有 {@link #retrieveForChat} 一条检索路径，问答与评测共用（D-28 把评测从容错路径迁过来）：
 * 向量化强制走真实 embedding 服务，失败整链重试，仍失败抛 {@link RetrievalFailedException}，
 * 由上层明确报错，绝不基于降级向量作答或算分。原容错路径 {@code retrieve} 已删，因为它那个
 * 哈希兜底并不像注释里曾经写的"维度不同、命中即噪声"——{@link HashEmbedder} 与
 * text-embedding-v3 <b>同为 1024 维</b>，混进真向量库不会报任何错，只会静默污染检索质量（D-14）。
 */
@Component
public class RetrievalService {

    /** 检索向量化的服务层重试次数（每次尝试内 embedding 客户端另有 2 次重试） */
    private static final int CHAT_ATTEMPTS = 3;

    /** 检索失败（向量化/向量库多次重试后仍不可用）：调用方应报错终止，不得继续生成 */
    public static class RetrievalFailedException extends RuntimeException {
        public RetrievalFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Store store;
    private final EmbeddingService embedding;
    private final ChunkStore chunks;
    private final Visibility visibility;

    public RetrievalService(Store store, EmbeddingService embedding, ChunkStore chunks, Visibility visibility) {
        this.store = store;
        this.embedding = embedding;
        this.chunks = chunks;
        this.visibility = visibility;
    }

    /**
     * 唯一检索入口（严格路径）：{@code user} 决定可见库，向量化必须来自真实 embedding 服务，
     * 失败整体重试 {@code CHAT_ATTEMPTS} 次；onRetry 回调携带从 1 开始的重试序号供 SSE 展示。
     * 仍失败抛 {@link RetrievalFailedException}——上层须直接报错，不得进入生成阶段。
     */
    public List<ChunkHit> retrieveForChat(User user, String question, List<String> kbIdFilter, IntConsumer onRetry) {
        // 问答链路上 ChatService 已用同一方法裁剪过范围，此处再求交为幂等操作（D-34）
        List<String> scopes = visibility.scopeFor(user, kbIdFilter);
        if (scopes.isEmpty()) return List.of();

        var rag = store.config.rag;
        List<String> queries = new ArrayList<>();
        queries.add(question);
        if (rag.queryRewrite) {
            for (String q : rewrite(question, rag.multiQueryCount)) queries.add(q);
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= CHAT_ATTEMPTS; attempt++) {
            try {
                List<double[]> vectors = embedding.embedBatchStrict(queries);
                return chunks.search(vectors, scopes, rag.topK, rag.scoreThreshold);
            } catch (RuntimeException e) {
                last = e;
                // 配置层就没有真实 embedding（D-14 拒绝假向量）时不重试：再多次也是同一个结果
                if (e instanceof HashVectorRejectedException) break;
                if (attempt < CHAT_ATTEMPTS) {
                    onRetry.accept(attempt);
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (last instanceof HashVectorRejectedException) {
            // 不是"试了没成功"，是"配置层就没法真向量"——文案要能直接指向修法
            throw new RetrievalFailedException(last.getMessage(), last);
        }
        throw new RetrievalFailedException(
                "向量化/检索调用失败（共尝试 " + CHAT_ATTEMPTS + " 次）: "
                        + (last == null ? "已中断" : last.getMessage()), last);
    }

    /** 向量库实现：pgvector / memory */
    public String vectorMode() {
        return chunks.mode();
    }

    /** 向量化实现：openai-embedding / hash-fallback */
    public String embedMode() {
        return embedding.name();
    }

    /** 查询改写：去停用词取关键词构造扩展查询（多路召回，与向量服务无关，保留规则实现） */
    private List<String> rewrite(String question, int count) {
        List<String> out = new ArrayList<>();
        String cleaned = question.replaceAll("[?？。！，,、\\s]", "")
                .replaceAll("(什么|怎么|如何|请问|哪些|流程是|的)", "");
        if (cleaned.length() > 2) {
            out.add(cleaned);
            if (count > 1 && cleaned.length() > 4) {
                out.add(cleaned.substring(0, Math.min(cleaned.length(), cleaned.length() / 2 + 2)));
            }
        }
        return out.stream().limit(Math.max(0, count)).toList();
    }
}
