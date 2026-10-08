package com.zhihu.dongcha.kb;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.common.Names;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.AuditLog;
import com.zhihu.dongcha.model.DocRecord;
import com.zhihu.dongcha.model.KnowledgeBase;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.rag.ChunkStore;
import com.zhihu.dongcha.rag.Visibility;
import com.zhihu.dongcha.redis.ChatCache;
import com.zhihu.dongcha.redis.CostService;
import com.zhihu.dongcha.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库（列表 JWT；写操作 KM_ADMIN+）。元数据 MySQL，分块/向量 PostgreSQL。
 * scope 四档可见性过滤保持不变。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private static final List<String> SCOPES = List.of("public", "internal", "dept", "confidential");

    private final DbStore db;
    private final Visibility visibility;
    private final ChunkStore chunkStore;
    private final CostService cost;
    private final ChatCache cache;

    public KnowledgeBaseController(DbStore db, Visibility visibility, ChunkStore chunkStore, CostService cost,
                                   ChatCache cache) {
        this.db = db;
        this.visibility = visibility;
        this.chunkStore = chunkStore;
        this.cost = cost;
        this.cache = cache;
    }

    /** 建库/改库共用请求体：name/scope/members；update 允许字段为 null 以做局部更新。*/
    public record KbRequest(String name, String scope, List<String> members) {
    }

    /** 返回当前用户可见的知识库列表（经 visibility 过滤后逐个映射为出参视图）。*/
    @GetMapping
    public Mono<List<Map<String, Object>>> list(ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
            User user = CurrentUser.of(ex);
            return visibility.visibleKbs(user).stream().map(this::view).toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 看板汇总：可见库数/总分块/向量化率(READY 占比)/文档数；向量率无文档时给 null，末尾补当日 token。*/
    @GetMapping("/overview")
    public Mono<Map<String, Object>> overview(ServerWebExchange ex) {
        User user = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            List<KnowledgeBase> kbs = visibility.visibleKbs(user);
            long totalChunks = 0;
            long vectorized = 0;
            long totalDocs = 0;
            for (KnowledgeBase kb : kbs) {
                totalChunks += chunkStore.countByKb(kb.id);
                for (DocRecord d : db.docsOfKb(kb.id)) {
                    totalDocs++;
                    if ("READY".equals(d.status)) vectorized++;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalChunks", totalChunks);
            m.put("kbCount", kbs.size());
            // 无分母时不给百分比：0 篇文档时"成功率 100%"是自证清白的假数
            m.put("vectorRate", totalDocs == 0 ? null : Math.round(vectorized * 10000.0 / totalDocs) / 100.0);
            m.put("totalDocs", totalDocs);
            m.put("bases", kbs.stream().map(this::view).toList());
            m.put("vectorStore", chunkStore.mode());
            return m;
        }).subscribeOn(Schedulers.boundedElastic())
                // 当日 LLM token 累计，取自 Redis（cost:tokens:{date}）；含 embedding 调用，不是"缓存"命中量
                .flatMap(m -> cost.tokensToday().map(t -> {
                    m.put("dailyLlmTokens", t);
                    return m;
                }));
    }

    /** 新建知识库：强校验后机密档需 ?confirm=true，生成 UUID、重名拒绝、落库并写审计。*/
    @PostMapping
    public Mono<Map<String, Object>> create(@RequestBody KbRequest req,
                                            @RequestParam(required = false) String confirm,
                                            ServerWebExchange ex) {
        User user = CurrentUser.of(ex);
        validate(req);
        if ("confidential".equals(req.scope()) && !"true".equals(confirm)) {
            throw new BizException(HttpStatus.PRECONDITION_REQUIRED, "CONFIRM_REQUIRED",
                    "创建机密知识库需二次确认：请携带 ?confirm=true 重试");
        }
        return Mono.fromCallable(() -> {
            KnowledgeBase kb = new KnowledgeBase();
            kb.id = UUID.randomUUID().toString();
            kb.name = req.name().trim();
            requireUniqueName(kb.name, kb.id);
            kb.scope = req.scope();
            kb.members = validateMembers(req.members());
            kb.owner = user.id;
            kb.ownerName = user.name;
            db.saveKb(kb);
            db.appendAudit(new AuditLog(user.account, "KB_CREATE", kb.name, "scope=" + kb.scope));
            return view(kb);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 局部更新库配置：字段为 null 即不改；禁止改成 confidential，scope/成员实际变更时失效问答缓存。*/
    @PutMapping("/{id}")
    public Mono<Map<String, Object>> update(@PathVariable String id, @RequestBody KbRequest req, ServerWebExchange ex) {
        User user = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            KnowledgeBase kb = require(id);
            if (req.scope() != null && !SCOPES.contains(req.scope())) throw BizException.badRequest("scope 非法");
            if ("confidential".equals(req.scope()) && !"confidential".equals(kb.scope)) {
                throw BizException.badRequest("不允许将已有知识库变更为机密库");
            }
            if (req.name() != null && !req.name().isBlank()) {
                String next = req.name().trim();
                if (next.length() < 2 || next.length() > 30)
                    throw BizException.badRequest("名称需 2-30 字");
                // 只在真的改名时判重：改成自己现在的名字不该被自己挡住
                if (!Names.key(next).equals(Names.key(kb.name))) requireUniqueName(next, kb.id);
                kb.name = next;
            }
            // D-7：成员形态先过校验，写错的成员过去是静默不可见
            List<String> nextMembers = req.members() == null ? null : validateMembers(req.members());
            boolean scopeOrMembersChanged =
                    (req.scope() != null && !req.scope().equals(kb.scope))
                            || (nextMembers != null && !nextMembers.equals(kb.members));
            if (req.scope() != null) kb.scope = req.scope();
            if (nextMembers != null) kb.members = nextMembers;
            db.saveKb(kb);
            if (scopeOrMembersChanged) cache.invalidate();
            db.appendAudit(new AuditLog(user.account, "KB_UPDATE", kb.name, "更新知识库配置"));
            return view(kb);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 删除知识库：库内仍有文档则拒；删元数据后清 PG 分块、内存分块缓存与问答缓存。*/
    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id, ServerWebExchange ex) {
        User user = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            KnowledgeBase kb = require(id);
            // D-30：EXISTS 一条查询，不再为了"这库里还有没有文档"把全表文档读进内存
            if (db.hasDocsInKb(id)) throw BizException.badRequest("知识库下仍有文档，请先删除文档");
            db.deleteKb(id);
            chunkStore.deleteByKb(id);
            db.cache().chunksByKb.remove(id);
            cache.invalidate();
            db.appendAudit(new AuditLog(user.account, "KB_DELETE", kb.name, "删除知识库"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", true);
            out.put("id", id);
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重名拒绝（D-2）：知识库名在写入时判重，口径见 {@link Names}（首尾空格与大小写不算差别）。
     * <p>为什么不上 UNIQUE：这套库是共享生产实例，存量数据里已经可能有同名库（约束加上去会让写入直接失败、
     * 应用启动期的 schema 迁移也会连带受影响），而"重名"是业务语义不是数据完整性。
     * <p>如实边界：这条检查读后写、不原子。两个人同时建同名库，两边都可能通过——没有 UNIQUE 就没有
     * 数据库级兜底，这是"不加约束"换来的代价。真出现重名时，改名或删掉其中一个即可纠正，无需清库。
     */
    private void requireUniqueName(String name, String selfId) {
        String key = Names.key(name);
        for (String other : db.otherKbNames(selfId)) {
            if (Names.key(other).equals(key)) {
                throw new BizException(HttpStatus.BAD_REQUEST, "KB_NAME_DUPLICATE",
                        "已存在同名知识库「" + name + "」：引用卡片只显示库名与文档名，"
                                + "两个同名的库会让人分不出答案出自哪一个。请换个名字，或先处理掉旧的库");
            }
        }
    }

    /** 建库入参强校验（仅 create 调用）：名称 2-30 字、scope 必须落在 SCOPES 四档内。*/
    private void validate(KbRequest req) {
        if (req == null || req.name() == null) throw BizException.badRequest("名称不能为空");
        String n = req.name().trim();
        if (n.length() < 2 || n.length() > 30) throw BizException.badRequest("名称需 2-30 字");
        if (req.scope() == null || !SCOPES.contains(req.scope()))
            throw BizException.badRequest("可见范围需为 public/internal/dept/confidential");
    }

    /**
     * 成员形态校验（D-7）：可见性判定只认四种能真的匹配上的写法——
     * 用户 id / 工号 / 账号（邮箱）/ {@code dept:部门名}（见 {@link Visibility#sharedWith}）。
     * 以前写错形态是**静默不可见**：库建了、成员填了、人看不见，排障时三处都"看着没错"。
     * 现在非 dept 形态必须在用户表里真实存在（走 MySQL 而非 60s TTL 内存镜像，
     * 免得刚建完账号就分享被误拒）；dept 形态允许暂时没人属于该部门（人员是后加的），
     * 但没匹配到人时打 WARN，让"这个成员现在还不生效"在日志里看得见。
     */
    private List<String> validateMembers(List<String> members) {
        if (members == null || members.isEmpty()) return List.of();
        if (members.size() > 200) throw BizException.badRequest("成员数量上限 200，当前 " + members.size());
        List<User> all = db.allUsers();
        List<String> out = new ArrayList<>();
        for (String raw : members) {
            String m = raw == null ? "" : raw.trim();
            if (m.isEmpty()) throw BizException.badRequest("成员项不能为空白");
            if (m.length() > 80) throw BizException.badRequest("成员项长度上限 80 字：" + clip(m));
            if (m.startsWith("dept:")) {
                String dept = m.substring(5).trim();
                if (dept.isEmpty()) throw BizException.badRequest("dept: 后面要写部门名，例如 dept:销售部");
                String normalized = "dept:" + dept;
                boolean any = all.stream().anyMatch(u -> dept.equals(u.dept));
                if (!any) log.warn("kb 成员 {} 当前没有任何用户属于该部门（后续加入该部门人员后即生效）", normalized);
                if (!out.contains(normalized)) out.add(normalized);
                continue;
            }
            boolean known = all.stream().anyMatch(
                    u -> m.equals(u.id) || equalsIgnoreCase(m, u.empNo) || equalsIgnoreCase(m, u.account));
            if (!known) {
                throw BizException.badRequest("成员「" + clip(m) + "」无法匹配任何用户（只接受用户 id / 工号 / 账号 / dept:部门名）。"
                        + "写错的成员不会报错、只会让那个人永远看不到这个库，所以这里直接拒绝");
            }
            if (!out.contains(m)) out.add(m);
        }
        return out;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return b != null && a.equalsIgnoreCase(b);
    }

    private static String clip(String s) {
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private KnowledgeBase require(String id) {
        return db.findKb(id).orElseThrow(() -> BizException.notFound("知识库不存在"));
    }

    /** 单库出参视图：实时查块数与逐篇文档状态聚合；list/overview 按库逐个调用它。*/
    private Map<String, Object> view(KnowledgeBase kb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.id);
        m.put("name", kb.name);
        m.put("scope", kb.scope);
        m.put("members", kb.members);
        // 全系统只有一个 embedding 模型（app.llm.embedding-model），按库挑模型是假的可选性
        m.put("embeddingModel", db.config().models.embedding.model);
        m.put("chunkCount", (int) chunkStore.countByKb(kb.id));
        long vectorized = 0;
        long total = 0;
        boolean unfinished = false;
        for (DocRecord d : db.docsOfKb(kb.id)) {
            total++;
            if ("READY".equals(d.status)) vectorized++;
            if ("PENDING".equals(d.status) || "PARSING".equals(d.status)) unfinished = true;
        }
        m.put("vectorizedCount", vectorized);
        m.put("docCount", total);
        m.put("status", deriveStatus(unfinished));
        m.put("owner", kb.ownerName);
        return m;
    }

    /** 状态由可观测事实推导：向量库当前是否可达 + 本库是否还有文档在入库队列里。持久化一个写死的 "connected" 等于谎报。 */
    private String deriveStatus(boolean unfinished) {
        if (!"pgvector".equals(chunkStore.mode())) return "disconnected";
        return unfinished ? "syncing" : "connected";
    }
}
