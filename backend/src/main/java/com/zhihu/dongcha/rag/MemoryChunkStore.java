package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.model.ChunkRecord;
import com.zhihu.dongcha.store.Store;

import java.util.*;

/**
 * 向量检索内存回退实现（PostgreSQL 不可用时使用）：进程内余弦相似度。
 * 与 pgvector 路径同维度（embedding 维度一致），因此真实 embedding 结果也能落到内存库。
 */
public class MemoryChunkStore implements ChunkStore {

    private final Store store;

    public MemoryChunkStore(Store store) {
        this.store = store;
    }

    /** 认为整批 chunk 同属一个文档（docId/kbId 只取首条）；空批直接返回，不动已有索引 */
    @Override
    public synchronized void replaceChunks(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) return;
        String docId = chunks.get(0).docId;
        String kbId = chunks.get(0).kbId;
        store.chunksByDoc.put(docId, new ArrayList<>(chunks));
        rebuildKb(kbId);
    }

    /** 先取首条拿到 kbId（删掉后就取不到了），删完重建该库的库级索引 */
    @Override
    public synchronized void deleteByDoc(String docId) {
        ChunkRecord sample = firstOf(docId);
        store.chunksByDoc.remove(docId);
        if (sample != null) rebuildKb(sample.kbId);
    }

    /** 连带删掉这些分块所属文档在 chunksByDoc 里的条目，否则两份视图会不一致 */
    @Override
    public synchronized void deleteByKb(String kbId) {
        List<ChunkRecord> removed = store.chunksByKb.remove(kbId);
        if (removed != null) {
            Set<String> docIds = new HashSet<>();
            for (ChunkRecord c : removed) docIds.add(c.docId);
            docIds.forEach(store.chunksByDoc::remove);
        }
    }

    @Override
    public long countByDoc(String docId) {
        return store.chunksByDoc.getOrDefault(docId, List.of()).size();
    }

    @Override
    public long countByKb(String kbId) {
        return store.chunksByKb.getOrDefault(kbId, List.of()).size();
    }

    /** 按文档维度求和；计数取自 chunksByDoc，检索取的却是 chunksByKb */
    @Override
    public long countAll() {
        return store.chunksByDoc.values().stream().mapToLong(List::size).sum();
    }

    @Override
    public Map<String, String> distinctDocs() {
        Map<String, String> out = new LinkedHashMap<>();
        store.chunksByDoc.forEach((docId, chunks) -> {
            if (!chunks.isEmpty()) {
                out.put(docId, chunks.get(0).docName + "（" + chunks.size() + " 块）");
            }
        });
        return out;
    }

    /** 越界的 offset/limit 被钳进 [0, size] 而不报错，返回的是副本，调用方改动不会污染索引 */
    @Override
    public List<ChunkRecord> listByDoc(String docId, int offset, int limit) {
        List<ChunkRecord> all = store.chunksByDoc.getOrDefault(docId, List.of());
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + limit, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    /** 无索引：把可见库的分块全扫一遍，vector 为 null 的分块（入库时没拿到向量）直接跳过 */
    @Override
    public List<ChunkHit> search(List<double[]> queryVectors, Collection<String> kbIds, int topK, double threshold) {
        Map<String, ChunkHit> best = new HashMap<>();
        for (String kbId : kbIds) {
            for (ChunkRecord c : store.chunksByKb.getOrDefault(kbId, List.of())) {
                if (c.vector == null) continue;
                double max = 0;
                for (double[] qv : queryVectors) {
                    double s = HashEmbedder.cosine(qv, c.vector);
                    if (s > max) max = s;
                }
                if (max < threshold) continue;
                ChunkHit hit = new ChunkHit(c, max);
                ChunkHit old = best.get(c.id);
                if (old == null || old.score() < max) best.put(c.id, hit);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(ChunkHit::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public String mode() {
        return "memory";
    }

    private ChunkRecord firstOf(String docId) {
        List<ChunkRecord> l = store.chunksByDoc.get(docId);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** 每次写后按 chunksByDoc 重算库级列表；结果为空时摘掉 key，检索就不会再遍历到一个空集合 */
    private void rebuildKb(String kbId) {
        List<ChunkRecord> merged = new ArrayList<>();
        for (DocIndex idx : docsOfKb(kbId)) merged.addAll(idx.chunks);
        if (merged.isEmpty()) store.chunksByKb.remove(kbId);
        else store.chunksByKb.put(kbId, merged);
    }

    /** 按 docId 字典序稳定合并，保证分块顺序可复现 */
    private List<DocIndex> docsOfKb(String kbId) {
        List<DocIndex> out = new ArrayList<>();
        for (Map.Entry<String, List<ChunkRecord>> e : store.chunksByDoc.entrySet()) {
            if (!e.getValue().isEmpty() && kbId.equals(e.getValue().get(0).kbId)) {
                out.add(new DocIndex(e.getKey(), e.getValue()));
            }
        }
        out.sort(Comparator.comparing(d -> d.docId));
        return out;
    }

    private record DocIndex(String docId, List<ChunkRecord> chunks) {
    }
}
