package com.zhihu.dongcha.document;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.common.Names;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.ChunkRecord;
import com.zhihu.dongcha.model.DocRecord;
import com.zhihu.dongcha.model.KnowledgeBase;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.rag.Visibility;
import com.zhihu.dongcha.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.codec.multipart.FilePart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 文档管理（读 JWT；上传/重试/删除 KM_ADMIN+，由 AuthWebFilter 拦截）。
 * 元数据落 MySQL，分块与向量落 PostgreSQL/pgvector，解析在后台线程池异步执行。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "xlsx", "txt", "md");
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DbStore db;
    private final Visibility visibility;
    private final IngestService ingest;
    private final Path uploadDir;
    /** D-16：上传体积上限（MB）。线上另有 nginx 兜底，本机裸跑时这是唯一一道闸 */
    private final long maxUploadBytes;

    public DocumentController(DbStore db, Visibility visibility, IngestService ingest,
                              @org.springframework.beans.factory.annotation.Value("${app.upload-dir}") String dir,
                              @org.springframework.beans.factory.annotation.Value("${app.upload.max-size-mb:50}") int maxUploadMb) {
        this.db = db;
        this.visibility = visibility;
        this.ingest = ingest;
        this.uploadDir = Path.of(dir).toAbsolutePath();
        this.maxUploadBytes = Math.max(1, maxUploadMb) * 1024 * 1024L;
    }

    /** 分页列出当前用户可见库内的文档，支持 keyword/status/kbId 过滤；库 id 非法/不可见的口径见方法内 D-40。*/
    @GetMapping
    public Mono<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String kbId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            User user = CurrentUser.of(ex);
            // 一次可见性读同时给出"能看哪些库"和"库叫什么"：D-30 后没有镜像，
            // 逐行再查 kbName 就是把一页文档变成一页 SQL 查询
            List<KnowledgeBase> visible = visibility.visibleKbs(user);
            Set<String> visibleIds = new HashSet<>();
            Map<String, String> kbNames = new HashMap<>();
            for (KnowledgeBase kb : visible) {
                visibleIds.add(kb.id);
                kbNames.put(kb.id, kb.name);
            }
            // D-40：库过滤要出声。写错或传别人的库 id 时**返回空列表**是最坏的形态——
            // 调用方会以为过滤生效了，拿着"这个库的文档"这份清单去做删除，实际拿的是全量。
            // 所以这里跟 upload 的验库口径一致：库不存在 400，库存在但看不见 403。
            String kbFilter = kbId == null || kbId.isBlank() ? null : kbId.trim();
            if (kbFilter != null) {
                KnowledgeBase kb = db.findKb(kbFilter).orElseThrow(() -> BizException.badRequest("知识库不存在: " + kbFilter));
                if (!visibility.canSee(user, kb)) throw BizException.forbidden("无权访问该知识库的文档");
            }
            List<DocRecord> filtered = new ArrayList<>();
            for (DocRecord d : db.allDocs()) {
                if (!visibleIds.contains(d.kbId)) continue;
                if (kbFilter != null && !kbFilter.equals(d.kbId)) continue;
                if (keyword != null && !keyword.isBlank() && !d.name.contains(keyword)) continue;
                if (status != null && !status.isBlank() && !status.equals(d.status)) continue;
                filtered.add(d);
            }
            filtered.sort(Comparator.comparingLong((DocRecord d) -> d.updatedAt).reversed());
            int total = filtered.size();
            // D-10：size 不钳制等于允许任何人一次拉全表并整体序列化，自我 DoS 面；页大小锁在 1..100
            int pageSize = Math.min(Math.max(size, 1), 100);
            int from = Math.min(Math.max(page - 1, 0) * pageSize, total);
            int to = Math.min(from + pageSize, total);
            List<Map<String, Object>> items = filtered.subList(from, to).stream()
                    .map(d -> view(d, kbNames.get(d.kbId))).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total);
            out.put("items", items);
            out.put("page", page);
            out.put("size", pageSize);
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 单文件 multipart 上传：扩展名限 pdf/docx/xlsx/txt/md，落盘校验通过后即返回，解析交后台 ingest。*/
    @PostMapping(value = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    // kbId 必须是 @RequestPart：前端 el-upload 与验收脚本都把它作为 multipart 表单字段发过来，
    // 换成 @RequestParam 就变成"只从 query 取"，WebFlux 不会回退去读表单字段 → 上传直接 400（包装成 500）
    public Mono<ResponseEntity<Map<String, Object>>> upload(@RequestPart("file") FilePart file,
                                                            @RequestPart("kbId") String kbId,
                                                            ServerWebExchange ex) {
        User user = CurrentUser.of(ex);
        String filename = file.filename() == null ? "untitled.txt" : Path.of(file.filename()).getFileName().toString();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_TYPE",
                    "仅支持 pdf/docx/xlsx/txt/md 格式，当前为: " + (ext.isEmpty() ? "无扩展名" : ext));
        }
        DocRecord doc = new DocRecord();
        doc.name = filename;
        doc.type = ext;
        doc.kbId = kbId;
        doc.status = "PARSING";
        doc.progress = 0;
        doc.stage = "queued";

        // D-30：知识库存在性与写权限现在是 MySQL 读，不能留在事件循环上；
        // 顺序仍是"先验库、再落盘"——无权写对方的库时连文件都不该碰。
        return Mono.fromCallable(() -> {
            KnowledgeBase kb = db.findKb(kbId).orElseThrow(() -> BizException.badRequest("知识库不存在"));
            if (!visibility.canSee(user, kb)) throw BizException.forbidden("无权写入该知识库");
            // 判重必须排在落盘之前：先写文件再拒绝会在 uploads 卷里留下一份没人认领的文件
            requireUniqueDocName(kbId, doc.name);
            try {
                Files.createDirectories(uploadDir);
            } catch (Exception e) {
                throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "上传目录不可用");
            }
            doc.filePath = uploadDir.resolve(doc.id + "." + ext).toString();
            return kb;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(kb -> file.transferTo(Path.of(doc.filePath))
                        .then(Mono.fromCallable(() -> {
                            // D-16：落盘后再校验体积。WebFlux 的 FilePart 不提供可信的 Content-Length，
                            // 而 transferTo 是流式写盘、不会把整个文件读进内存，所以磁盘上的实际大小才是唯一依据。
                            requireWithinSizeLimit(Path.of(doc.filePath));
                            db.saveDoc(doc);            // 元数据落 MySQL
                            ingest.submit(doc.id);      // 后台解析：分块 → embedding → pgvector
                            return ResponseEntity.status(HttpStatus.CREATED).body(view(doc, kb.name));
                        }).subscribeOn(Schedulers.boundedElastic())))
                .onErrorResume(BizException.class, e -> Mono.error(e))
                .onErrorResume(e -> Mono.error(new BizException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED", "文件保存失败: " + e.getMessage())));
    }

    /**
     * 同一知识库内同名文档拒绝（D-2）：口径见 {@link Names}。
     * <p>为什么必须拒：回答的引用卡片只显示文档名（加分块序号/页码），同库里两份"报价制度.pdf"
     * 内容不同，用户与排障的人都无法从名字回溯到具体哪一篇；检索侧也只会按名字去对齐期望文档。
     * <p>为什么不上 UNIQUE：共享生产实例，重名的历史行可能已经存在，加约束会让写入直接失败。
     * <p>如实边界：读后写、不原子——同时上传同名文件仍可能都通过。要重新入库旧的那份，
     * 先删除文档再传（删除会一并清掉向量与落盘文件）。
     */
    private void requireUniqueDocName(String kbId, String filename) {
        String key = Names.key(filename);
        for (String existing : db.docNamesInKb(kbId)) {
            if (Names.key(existing).equals(key)) {
                throw new BizException(HttpStatus.BAD_REQUEST, "DOC_NAME_DUPLICATE",
                        "该知识库里已有同名文档「" + filename + "」：同名文档在引用里分不出来源。"
                                + "要替换请先删除旧文档，或改名后再上传");
            }
        }
    }

    /** 空文件与超限文件都拒收，并清掉刚落盘的那份，不在 uploads 卷里留垃圾（D-16、顺带收敛 D-17） */
    private void requireWithinSizeLimit(Path target) throws IOException {
        long bytes = Files.size(target);
        if (bytes > maxUploadBytes || bytes == 0) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                log.warn("oversized upload cleanup failed: {}", e.toString());
            }
            throw BizException.badRequest(bytes == 0 ? "文件内容为空，未入库"
                    : String.format("文件超过上限 %dMB（当前 %.1fMB），未入库",
                    maxUploadBytes / 1048576, bytes / 1048576.0));
        }
    }

    /** 单篇文档详情：filePath 属服务器内部路径故出参置空，并附当前向量库形态。*/
    @GetMapping("/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable String id, ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            DocRecord doc = requireVisible(id, ex);
            Map<String, Object> m = new LinkedHashMap<>(view(doc));
            m.put("filePath", null);
            m.put("createdAt", doc.createdAt);
            m.put("vectorStore", ingest.vectorStoreMode());
            return m;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 分页预览某文档分块：每页固定 10 条（不受入参控制），page=0（无页码概念）归一为 null。*/
    @GetMapping("/{id}/chunks")
    public Mono<List<Map<String, Object>>> chunks(@PathVariable String id,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            requireVisible(id, ex);
            int size = 10;
            int offset = Math.max(page - 1, 0) * size;
            List<ChunkRecord> list = ingest.listChunks(id, offset, size);
            List<Map<String, Object>> out = new ArrayList<>();
            for (ChunkRecord c : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", c.index);
                m.put("text", c.text);
                m.put("charCount", c.charCount);
                m.put("page", c.page > 0 ? c.page : null);
                out.add(m);
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 重新入队解析：FAILED 或"READY 但向量全空"均可，重置进度前先纠正已失效的落盘路径。*/
    @PostMapping("/{id}/retry")
    public Mono<Map<String, Object>> retry(@PathVariable String id, ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            DocRecord doc = requireVisible(id, ex);
            // 除失败外，还允许修复"元数据声称已入库、向量库里却一块都没有"的分叉：
            // 这类文档状态是 READY，走不到 FAILED 分支，否则永久无法重新入库。
            boolean vectorMissing = "READY".equals(doc.status) && doc.chunkCount > 0
                    && ingest.countChunksOf(doc.id) == 0;
            if (!"FAILED".equals(doc.status) && !vectorMissing) {
                throw BizException.badRequest("仅失败或向量缺失的文档可重试，当前状态: " + doc.status);
            }
            doc.status = "PARSING";
            doc.progress = 0;
            doc.stage = "queued";
            doc.failReason = null;
            doc.updatedAt = System.currentTimeMillis();
            relocateStaleFile(doc);
            db.saveDoc(doc);
            ingest.submit(doc.id);
            return view(doc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * filePath 存的是上传当时的绝对路径；数据目录整体搬家后它会失效，导致文档永远无法重新入库。
     * 若当前上传目录下有同名文件，就把它纠正回来（仅纠正记录，不移动文件）。
     */
    private void relocateStaleFile(DocRecord doc) {
        if (doc.filePath == null) return;
        Path stored = Path.of(doc.filePath);
        if (Files.exists(stored)) return;
        Path name = stored.getFileName();
        if (name == null) return;
        Path moved = uploadDir.resolve(name.toString());
        if (Files.exists(moved)) {
            log.info("doc {} 的存储路径已失效，纠正为 {}", doc.id, moved);
            doc.filePath = moved.toString();
        }
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id, ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            DocRecord doc = requireVisible(id, ex);
            db.deleteDoc(doc.id);           // MySQL 元数据
            ingest.removeChunks(doc.id);    // PG chunks（级联删向量）
            deleteStoredFile(doc);          // 上传文件，否则 data/uploads 只增不减
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", true);
            out.put("id", id);
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 删除文档落盘文件；播种语料在 classpath（无 filePath），只碰上传目录内的文件 */
    private void deleteStoredFile(DocRecord doc) {
        if (doc.filePath == null) return;
        try {
            Path file = Path.of(doc.filePath).toAbsolutePath().normalize();
            if (!file.startsWith(uploadDir)) {
                log.warn("doc {} 的 filePath 不在上传目录内，跳过删除: {}", doc.id, file);
                return;
            }
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("删除上传文件失败 doc={} path={}: {}", doc.id, doc.filePath, e.toString());
        }
    }

    /** 载入文档并校验其所属库对当前用户可见（不存在 404、无权 403），detail/chunks/retry/delete 共用。*/
    private DocRecord requireVisible(String id, ServerWebExchange ex) {
        DocRecord doc = db.findDoc(id).orElseThrow(() -> BizException.notFound("文档不存在"));
        User user = CurrentUser.of(ex);
        if (!visibility.visibleKbIds(user).contains(doc.kbId)) {
            throw BizException.forbidden("无权访问该文档");
        }
        return doc;
    }

    /** view 便捷重载：为单篇文档额外查一次库名；列表场景请用带 kbName 的重载以免逐行查库。*/
    private Map<String, Object> view(DocRecord d) {
        return view(d, db.findKb(d.kbId).map(k -> k.name).orElse(null));
    }

    /** 组装文档出参；chunkCount 为 0 时回退实时统计向量库，避免元数据与实际分块数分叉。*/
    private Map<String, Object> view(DocRecord d, String kbName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("name", d.name);
        m.put("type", d.type);
        m.put("kbId", d.kbId);
        m.put("kbName", kbName);
        m.put("chunkCount", d.chunkCount > 0 ? d.chunkCount : (int) ingest.countChunksOf(d.id));
        m.put("status", d.status);
        m.put("progress", d.progress);
        m.put("stage", d.stage);
        m.put("failReason", d.failReason);
        m.put("updatedAt", d.updatedAt);
        return m;
    }
}
