package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.model.ChunkRecord;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量库抽象：真实实现为 PostgreSQL + pgvector（{@link PgChunkStore}），
 * PostgreSQL 不可用时回退进程内余弦检索（{@link MemoryChunkStore}）。
 */
public interface ChunkStore {

    /** 覆盖式写入某文档的全部 chunk（含向量） */
    void replaceChunks(List<ChunkRecord> chunks);

    /** 只清向量侧行，文档元数据不归这里；IngestService.removeChunks 与启动对账清孤儿向量都走它 */
    void deleteByDoc(String docId);

    /** 按整个知识库清理向量；删库接口先要求库内已无文档，再调这里做向量侧收尾 */
    void deleteByKb(String kbId);

    /** 该文档当前已入库的分块数，文档列表/详情用 */
    long countByDoc(String docId);

    /** 该库分块总数；知识库列表是逐库各查一次，不是一条聚合 SQL */
    long countByKb(String kbId);

    /** 全部分块数：就绪日志、启动摘要与监控用，降级探测也拿它当连通性检查 */
    long countAll();

    /** 库里现存 chunk 归属的文档：docId → 文档名，供启动时对账孤儿向量 */
    Map<String, String> distinctDocs();

    /** 分页读取 chunk（chunk_index 升序），供 /documents/{id}/chunks 使用 */
    List<ChunkRecord> listByDoc(String docId, int offset, int limit);

    /**
     * 多路向量近邻检索：对每个 query 向量取 topK，按 chunk 取最大 score 合并，
     * score = 1 - cosine distance，低于 threshold 丢弃。
     */
    List<ChunkHit> search(List<double[]> queryVectors, Collection<String> kbIds, int topK, double threshold);

    /** 当前生效实现：pgvector / memory */
    String mode();
}
