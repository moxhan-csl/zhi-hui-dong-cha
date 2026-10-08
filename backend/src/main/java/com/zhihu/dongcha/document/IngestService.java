package com.zhihu.dongcha.document;

import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.ChunkRecord;
import com.zhihu.dongcha.model.DocRecord;
import com.zhihu.dongcha.rag.ChunkStore;
import com.zhihu.dongcha.rag.EmbeddingService;
import com.zhihu.dongcha.redis.ChatCache;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步解析流水线：抽取文本（PDF 用 PDFBox 逐页抽，其余走 Tika；TXT/MD 直读）→ 每页各自按 TextChunker 配置重叠分块（块不跨页）→
 * 分批向量化（模型与批大小由 app.llm.embedding-* 配置，单批重试 2 次仍失败则标 FAILED）
 * → 写入 PostgreSQL/pgvector chunks 表（PG 不可用时写内存回退）。
 * 进度与状态写穿 MySQL documents 表，供前端轮询。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DbStore db;
    private final TextChunker chunker;
    private final EmbeddingService embedding;
    private final ChunkStore chunks;
    private final ChatCache cache;
    private final int embedBatchSize;
    private final ExecutorService queue = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ingest-worker");
        t.setDaemon(true);
        return t;
    });

    public IngestService(DbStore db, TextChunker chunker, EmbeddingService embedding, ChunkStore chunks,
                         ChatCache cache,
                         @Value("${app.llm.embedding-batch-size}") int embedBatchSize) {
        this.db = db;
        this.chunker = chunker;
        this.embedding = embedding;
        this.chunks = chunks;
        this.cache = cache;
        this.embedBatchSize = embedBatchSize;
    }

    /** 上传/重试提交（非阻塞入队） */
    public void submit(String docId) {
        queue.submit(() -> {
            try {
                process(docId);
            } catch (Exception e) {
                log.warn("ingest failed doc={} err={}", docId, e.toString());
                markFailed(docId, e.getMessage());
            }
        });
    }

    /**
     * 进度口径：解析流水线固定 4 个阶段（抽取 → 分块 → 向量化 → 写库），每阶段等权 25%；
     * 只有向量化阶段有可测量的子进度（已完成 embedding 批次 / 总批次），其余阶段结束时才跳到下一档。
     * <p>D-30：文档对象按 id 从 MySQL 载入，stage 随进度写穿到 documents.stage 列
     * （内存镜像删掉后，落库是前端轮询看到阶段的唯一途径）。
     * <p>每次写穿都先确认文档行还在：解析跑到一半时用户删了文档，
     * 照旧对象 {@code save} 会把这篇已删文档重新写回元数据表。
     */
    private void process(String docId) {
        DocRecord doc = db.findDoc(docId).orElse(null);
        if (doc == null) {
            log.info("doc {} 不存在（已删除），跳过解析", docId);
            return;
        }
        try {
            doc.status = "PARSING";
            doc.failReason = null;
            doc.progress = 0;
            doc.stage = "extracting";
            if (!touch(doc)) {
                abortDeleted(docId);
                return;
            }

            // 1. 抽取文本：PDF 逐页（拿到真实页边界），其余整篇
            List<PageSegment> segments = extractSegments(doc);
            if (segments.isEmpty()) throw new IllegalStateException("未能从文件中抽取到文本内容");
            doc.progress = 25;
            doc.stage = "chunking";
            if (!touch(doc)) {
                abortDeleted(docId);
                return;
            }

            // 2. 分块：每页各自分块，块不跨页——跨页的块没有唯一页码，引用卡片也就无从定位
            List<String> pieces = new ArrayList<>();
            List<Integer> piecePages = new ArrayList<>();
            for (PageSegment s : segments) {
                for (String piece : chunker.chunk(s.text(), TextChunker.SIZE, TextChunker.OVERLAP)) {
                    pieces.add(piece);
                    piecePages.add(s.page());
                }
            }
            if (pieces.isEmpty()) throw new IllegalStateException("分块结果为空");
            doc.progress = 50;
            doc.stage = "embedding";
            if (!touch(doc)) {
                abortDeleted(docId);
                return;
            }

            // 3. 批量向量化（真实 embedding，单批失败重试 2 次仍失败 → 抛出 → FAILED）
            //    按批调用而非整篇一次，进度才能反映真实完成的批次数
            List<double[]> vectors = new ArrayList<>();
            int batch = Math.max(1, Math.min(embedBatchSize, 10));
            int totalBatches = (pieces.size() + batch - 1) / batch;
            int doneBatches = 0;
            for (int from = 0; from < pieces.size(); from += batch) {
                List<String> group = pieces.subList(from, Math.min(pieces.size(), from + batch));
                vectors.addAll(embedding.embedBatchStrict(group));
                doneBatches++;
                doc.progress = 50 + (int) Math.round(25.0 * doneBatches / totalBatches);
                if (!touch(doc)) {
                    abortDeleted(docId);
                    return;
                }
            }
            doc.stage = "writing";

            // 4. 写入向量库（PG chunks 表 / 内存回退）
            List<ChunkRecord> records = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                String piece = pieces.get(i);
                ChunkRecord c = new ChunkRecord();
                c.id = docId + "-c" + i;
                c.docId = docId;
                c.docName = doc.name;
                c.kbId = doc.kbId;
                c.index = i;
                c.text = piece;
                c.charCount = piece.length();
                // 来自抽取阶段的真实页码；0 = 该格式没有页边界概念，或 PDF 逐页失败退回整篇。
                // 绝不编造"每 N 块 = 1 页"——那会让引用显示假页码（D-4 原本的病灶）
                c.page = piecePages.get(i);
                c.vector = i < vectors.size() ? vectors.get(i) : null;
                records.add(c);
            }
            chunks.replaceChunks(records);
            // 分块内容变了，此前缓存的回答可能引用已被替换的片段——整体失效
            cache.invalidate();

            doc.chunkCount = records.size();
            doc.progress = 100;
            doc.stage = "done";
            doc.status = "READY";
            if (!touch(doc)) {
                // 元数据行已经没了，刚写进去的向量就是孤儿，就地清掉
                abortDeleted(docId);
                return;
            }
            log.info("ingest ok doc={} chunks={} vectorStore={} embedder={}",
                    docId, records.size(), chunks.mode(), embedding.name());
        } catch (Exception e) {
            markFailed(docId, rootMessage(e));
        }
    }

    /** 抽取产物：一段带页码的文本。page 从 1 起；0 = 没有页边界概念（txt/md/docx/xlsx，以及 PDF 兜底） */
    record PageSegment(int page, String text) {
    }

    /** 按扩展名分派抽取：txt/md 直读(剥 BOM,无页→0)，pdf 先逐页(PDFBox)失败退整篇，其余走 Tika(页码 0)。*/
    private List<PageSegment> extractSegments(DocRecord doc) throws Exception {
        Path path = Path.of(doc.filePath);
        if (!Files.exists(path)) throw new IllegalStateException("上传文件不存在: " + doc.filePath);
        String type = doc.type == null ? "" : doc.type.toLowerCase();
        if (type.equals("txt") || type.equals("md")) {
            String s = Files.readString(path, StandardCharsets.UTF_8);
            String text = !s.isEmpty() && s.charAt(0) == 0xFEFF ? s.substring(1) : s;
            return text.isBlank() ? List.of() : List.of(new PageSegment(0, text));
        }
        if (type.equals("pdf")) {
            List<PageSegment> pages = extractPdfPages(path);
            if (pages != null && !pages.isEmpty()) return pages;
            // 逐页没拿到文本（加密、纯扫描件无文本层、版面异常）→ 退回整篇：内容照样入库，页码如实留未知
            log.warn("pdf 逐页抽取无文本产出，退回整篇抽取（页码未知）doc={}", doc.id);
        }
        String text = extractTika(path);
        return text == null || text.isBlank() ? List.of() : List.of(new PageSegment(0, text));
    }

    /**
     * PDFBox 逐页抽文本，空白页跳过（不给它造块）。
     * 返回 null 表示这份 PDF 打不开，交给调用方退回 Tika 走原有失败口径；空列表表示打开了但没抽到文本。
     */
    private List<PageSegment> extractPdfPages(Path path) {
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageSegment> out = new ArrayList<>();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = stripper.getText(pdf);
                if (t != null && !t.isBlank()) out.add(new PageSegment(p, t));
            }
            return out;
        } catch (Exception e) {
            log.warn("PDFBox 逐页抽取失败 path={}: {}", path, e.toString());
            return null;
        }
    }

    /** Tika 自动识别格式抽取整篇纯文本；BodyContentHandler(-1) 取消长度上限，产出单段不分页文本。*/
    private String extractTika(Path path) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set("resourceName", path.getFileName().toString());
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, handler, metadata, new ParseContext());
        }
        return handler.toString();
    }

    /** 删除某文档全部分块并整体失效问答缓存（文档删除时由 DocumentController 调用）。*/
    public void removeChunks(String docId) {
        chunks.deleteByDoc(docId);
        cache.invalidate();
    }

    /** 删除某知识库全部分块并整体失效问答缓存（库级清理入口）。*/
    public void removeKbChunks(String kbId) {
        chunks.deleteByKb(kbId);
        cache.invalidate();
    }

    /** 直接统计向量库中该文档的分块数；元数据与实际不一致时（重试判定、视图兜底）取此权威值。*/
    public long countChunksOf(String docId) {
        return chunks.countByDoc(docId);
    }

    public long totalChunks() {
        return chunks.countAll();
    }

    /** 从向量库按 offset/limit 分页读某文档分块，供分块预览接口使用。*/
    public List<ChunkRecord> listChunks(String docId, int offset, int limit) {
        return chunks.listByDoc(docId, offset, limit);
    }

    public String vectorStoreMode() {
        return chunks.mode();
    }

    /** 解析失败收尾：标 FAILED(原因截断 200 字)、清已写入的向量并失效缓存、归零分块数；文档已删则不写。*/
    private void markFailed(String docId, String reason) {
        DocRecord doc = db.findDoc(docId).orElse(null);
        if (doc == null) {
            log.info("doc {} 已被删除，不写失败状态", docId);
            return;
        }
        doc.status = "FAILED";
        doc.progress = 0;
        doc.stage = "failed";
        doc.failReason = reason == null ? "解析失败"
                : (reason.length() > 200 ? reason.substring(0, 200) : reason);
        if (!touch(doc)) return;
        chunks.deleteByDoc(docId);
        // 片段已被清掉，引用它们的缓存回答不能再放出去
        cache.invalidate();
        doc.chunkCount = 0;
        touch(doc);
    }

    /**
     * 写穿进度与阶段。
     *
     * @return false 表示文档行已不存在（解析期间被删），调用方必须中止；
     *         MySQL 抖动只记 WARN 并继续——一次超时就放弃整篇解析比把它跑完更浪费已经花掉的 embedding 调用
     */
    private boolean touch(DocRecord doc) {
        doc.updatedAt = System.currentTimeMillis();
        try {
            if (!db.saveDocIfPresent(doc)) {
                log.info("doc {} 在解析期间已被删除，中止解析", doc.id);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("persist doc {} failed: {}", doc.id, e.toString());
            return true;
        }
    }

    /** 解析中途因删除而中止：把已经写进向量库的片段清掉，别留孤儿（启动对账是最后一道，不是唯一一道） */
    private void abortDeleted(String docId) {
        try {
            chunks.deleteByDoc(docId);
        } catch (Exception e) {
            log.warn("中止解析后清理 doc {} 的向量失败: {}", docId, e.toString());
        }
    }

    /** 沿 getCause 链走到根因取 message；自引用 cause 会中断循环，避免包装异常掩盖真因。*/
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    /** 应用关闭时 shutdownNow 中断摄取线程池(ingest-worker)，叫停仍在跑的解析批次。*/
    @PreDestroy
    public void shutdown() {
        queue.shutdownNow();
    }
}
