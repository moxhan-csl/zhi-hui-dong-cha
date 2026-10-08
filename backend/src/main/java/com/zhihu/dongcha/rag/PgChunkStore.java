package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.model.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * PostgreSQL + pgvector 向量库（chunks 表）。
 * 表结构（幂等创建）：id bigserial PK, doc_id, kb_id, doc_name, chunk_index, page, char_count,
 * content text, embedding vector(dim)；HNSW cosine 索引。
 * 检索：ORDER BY embedding <=> ?::vector LIMIT topK，score = 1 - distance，按可见 kb_id 过滤。
 */
public class PgChunkStore implements ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(PgChunkStore.class);

    private final JdbcTemplate jdbc;
    private final int dim;

    public PgChunkStore(JdbcTemplate jdbc, int dim) {
        this.jdbc = jdbc;
        this.dim = dim;
    }

    /** 连通性 + schema 幂等初始化；失败抛异常由调用方决定降级 */
    public void initSchema() {
        jdbc.query("SELECT 1", rs -> {
        });
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id           bigserial PRIMARY KEY,
                    doc_id       varchar(64)  NOT NULL,
                    kb_id        varchar(64)  NOT NULL,
                    doc_name     varchar(255),
                    chunk_index  int          NOT NULL,
                    page         int          NOT NULL DEFAULT 1,
                    char_count   int          NOT NULL DEFAULT 0,
                    content      text         NOT NULL,
                    embedding    vector(""" + dim + "))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_doc_idx ON chunks (doc_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_kb_idx ON chunks (kb_id)");
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw "
                    + "ON chunks USING hnsw (embedding vector_cosine_ops)");
        } catch (Exception e) {
            log.warn("HNSW 索引创建失败（pgvector 版本不支持？），退化为顺序扫描: {}", e.getMessage());
        }
        Integer wrongDim = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NOT NULL AND vector_dims(embedding) <> " + dim,
                Integer.class);
        if (wrongDim != null && wrongDim > 0) {
            log.warn("检测到 {} 行向量维度与配置 dim={} 不一致，清空 chunks 重新入库", wrongDim, dim);
            jdbc.execute("TRUNCATE TABLE chunks RESTART IDENTITY");
        }
    }

    /** 覆盖式写入：先按 doc_id 删旧行再批量插入，两条语句不在同一事务里（调用链上无 @Transactional） */
    @Override
    public void replaceChunks(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) return;
        String docId = chunks.get(0).docId;
        jdbc.update("DELETE FROM chunks WHERE doc_id = ?", docId);
        List<Object[]> batch = new ArrayList<>();
        for (ChunkRecord c : chunks) {
            batch.add(new Object[]{c.docId, c.kbId, c.docName, c.index, c.page, c.charCount,
                    c.text, toVector(c.vector)});
        }
        jdbc.batchUpdate("""
                INSERT INTO chunks(doc_id, kb_id, doc_name, chunk_index, page, char_count, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)""", batch);
    }

    @Override
    public void deleteByDoc(String docId) {
        jdbc.update("DELETE FROM chunks WHERE doc_id = ?", docId);
    }

    @Override
    public void deleteByKb(String kbId) {
        jdbc.update("DELETE FROM chunks WHERE kb_id = ?", kbId);
    }

    @Override
    public long countByDoc(String docId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE doc_id = ?", Long.class, docId);
        return n == null ? 0 : n;
    }

    @Override
    public long countByKb(String kbId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE kb_id = ?", Long.class, kbId);
        return n == null ? 0 : n;
    }

    @Override
    public long countAll() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunks", Long.class);
        return n == null ? 0 : n;
    }

    /** 每个 doc_id 出一行；doc_name 用 max() 只是为满足 GROUP BY，不是"取最新名称" */
    @Override
    public Map<String, String> distinctDocs() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT doc_id, max(doc_name) AS doc_name, count(*) AS n FROM chunks GROUP BY doc_id",
                rs -> {
                    out.put(rs.getString("doc_id"), rs.getString("doc_name") + "（" + rs.getInt("n") + " 块）");
                });
        return out;
    }

    /** 注意绑参顺序按 SQL 是 limit 在前、offset 在后，与方法形参 (offset, limit) 相反 */
    @Override
    public List<ChunkRecord> listByDoc(String docId, int offset, int limit) {
        return jdbc.query("""
                SELECT id, doc_id, kb_id, doc_name, chunk_index, page, char_count, content
                FROM chunks WHERE doc_id = ? ORDER BY chunk_index ASC LIMIT ? OFFSET ?""",
                (rs, i) -> map(rs), docId, limit, offset);
    }

    /** kbIds 为空直接返回空列表，不会退化成全库检索；topK 由 SQL 的 LIMIT 控制，threshold 是取回后才在 Java 侧过滤 */
    @Override
    public List<ChunkHit> search(List<double[]> queryVectors, Collection<String> kbIds, int topK, double threshold) {
        if (kbIds.isEmpty() || queryVectors.isEmpty()) return List.of();
        List<String> scope = new ArrayList<>(kbIds);
        String placeholders = String.join(",", Collections.nCopies(scope.size(), "?"));
        String sql = "SELECT id, doc_id, kb_id, doc_name, chunk_index, page, char_count, content,"
                + " 1 - (embedding <=> ?::vector) AS score"
                + " FROM chunks WHERE kb_id IN (" + placeholders + ") AND embedding IS NOT NULL"
                + " ORDER BY embedding <=> ?::vector LIMIT ?";
        Map<String, ChunkHit> best = new HashMap<>();
        for (double[] qv : queryVectors) {
            // 同一个向量绑两次：args[0] 给 SELECT 里算 score 的 ?::vector，倒数第二个给 ORDER BY 的那个
            Object[] args = new Object[scope.size() + 3];
            args[0] = toVector(qv);
            for (int i = 0; i < scope.size(); i++) args[i + 1] = scope.get(i);
            args[args.length - 2] = toVector(qv);
            args[args.length - 1] = topK;
            jdbc.query(sql, rs -> {
                double score = rs.getDouble("score");
                if (score < threshold) return;
                ChunkRecord c = map(rs);
                ChunkHit old = best.get(c.id);
                if (old == null || old.score() < score) best.put(c.id, new ChunkHit(c, score));
            }, args);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(ChunkHit::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public String mode() {
        return "pgvector";
    }

    // ---------- helpers ----------

    /** 只映射展示字段，不读 embedding 列，所以这里产出的 ChunkRecord.vector 恒为 null */
    private ChunkRecord map(ResultSet rs) throws SQLException {
        ChunkRecord c = new ChunkRecord();
        c.id = String.valueOf(rs.getLong("id"));
        c.docId = rs.getString("doc_id");
        c.kbId = rs.getString("kb_id");
        c.docName = rs.getString("doc_name");
        c.index = rs.getInt("chunk_index");
        c.page = rs.getInt("page");
        c.charCount = rs.getInt("char_count");
        c.text = rs.getString("content");
        return c;
    }

    /** pgvector 文本字面量：[v1,v2,...]，配合 ?::vector 使用 */
    private String toVector(double[] v) {
        if (v == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
