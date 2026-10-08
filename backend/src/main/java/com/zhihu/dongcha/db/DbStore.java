package com.zhihu.dongcha.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.model.*;
import com.zhihu.dongcha.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 业务持久化门面（JPA Repository 之上的写穿层）。
 * <p>读路径（D-30）：知识库/文档/会话/评测一律由调用方直读 MySQL（阻塞 JPA），由调用方包在
 * {@code Mono.fromCallable(..).subscribeOn(Schedulers.boundedElastic())} 中执行——
 * 进程内不再保留这几张表的镜像，内存占用与库里的行数脱钩，重启也不需要全表载入。
 * 只有 users 表保留镜像供 {@code AuthWebFilter} 每请求鉴权使用（按 TTL 重载，量级由管理员控制）。
 * <p>写路径：所有变更经本类落到 MySQL，进程重启数据不丢。
 */
@Component
public class DbStore {

    private static final Logger log = LoggerFactory.getLogger(DbStore.class);

    private final Store store;
    private final UserRepository users;
    private final KnowledgeBaseRepository kbs;
    private final DocumentRepository docs;
    private final ConversationRepository conversations;
    private final EvalRunRepository evalRuns;
    private final SettingModelRepository settingModels;
    private final SettingRagRepository settingRag;
    private final AuditLogRepository auditLogs;
    /** 探活读数用的文档数，由 TTL 定时刷新（D-30：documents 镜像已删） */
    private volatile long docCount;

    /** Spring 注入全部仓库与 yml 里的模型默认值；构造时先把 yml 配置写进内存 store 兜底，settings 表有值时再覆盖 */
    public DbStore(Store store, UserRepository users, KnowledgeBaseRepository kbs, DocumentRepository docs,
                   ConversationRepository conversations, EvalRunRepository evalRuns,
                   SettingModelRepository settingModels, SettingRagRepository settingRag,
                   AuditLogRepository auditLogs,
                   @org.springframework.beans.factory.annotation.Value("${app.llm.base-url}") String llmBaseUrl,
                   @org.springframework.beans.factory.annotation.Value("${app.llm.chat-model}") String chatModel,
                   @org.springframework.beans.factory.annotation.Value("${app.llm.embedding-model}") String embeddingModel,
                   @org.springframework.beans.factory.annotation.Value("${app.llm.temperature}") double temperature) {
        this.store = store;
        this.users = users;
        this.kbs = kbs;
        this.docs = docs;
        this.conversations = conversations;
        this.evalRuns = evalRuns;
        this.settingModels = settingModels;
        this.settingRag = settingRag;
        this.auditLogs = auditLogs;
        // yml 中的真实模型配置作为默认值（settings_models 表有值时被覆盖）
        store.config.models.chat.baseUrl = llmBaseUrl;
        store.config.models.chat.model = chatModel;
        store.config.models.chat.temperature = temperature;
        store.config.models.embedding.model = embeddingModel;
    }

    /** 交出内存 Store 句柄：外部（探活、删库清 chunksByKb）直接读 users 镜像与临时映射 */
    public Store cache() {
        return store;
    }

    /** 运行期配置（models / rag），MySQL 为准、内存镜像加速读 */
    public AppConfig config() {
        return store.config;
    }

    // ==================== 启动加载 ====================

    /**
     * 载入鉴权镜像与运行期配置（D-30 后只剩这两件）。
     * 业务表不再整表灌进内存：可见性判定改为直读 MySQL，启动对账各用一条自己的定向查询，
     * 载入失败时也就只有"users 镜像为空"这一个后果，不会再出现"照着半截镜像去清向量库"。
     */
    public void loadAll() {
        store.users.clear();
        for (UserEntity e : users.findAll()) store.users.put(e.id, toUser(e));

        loadModels();
        loadRag();
        docCount = docs.count();
        log.info("mysql loaded: users={}（镜像）| 业务表直读 MySQL: kbs={} docs={} conversations={} evalRuns={}",
                store.users.size(), kbs.count(), docCount, conversations.count(), evalRuns.count());
    }

    /**
     * D-30：users 镜像按 TTL 重载（默认 60s）。运维直接改库（停用/删除账号）后最迟一个周期生效，
     * 不必重启进程——在此之前鉴权过滤器 {@code AuthWebFilter} 读的是启动时的快照。
     * <p>只重载 users 这张小表；其余业务表已经没有镜像。
     * MySQL 抖动时保留旧镜像：宁可放行一个刚在库侧被停用的账号几分钟，
     * 也不能因为一次查询超时就全站 401。增量替换（先补后删），不存在"清空后重灌"的空窗。
     */
    @Scheduled(initialDelayString = "${app.store.user-cache-ttl-seconds:60}",
            fixedDelayString = "${app.store.user-cache-ttl-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void refreshUserMirror() {
        try {
            Map<String, User> fresh = new LinkedHashMap<>();
            for (UserEntity e : users.findAll()) {
                User u = toUser(e);
                fresh.put(u.id, u);
            }
            store.users.putAll(fresh);
            store.users.keySet().retainAll(fresh.keySet());
            log.debug("user mirror refreshed: {}", fresh.size());
        } catch (Exception ex) {
            log.warn("user mirror refresh failed, keep previous snapshot: {}", ex.toString());
        }
    }

    /**
     * 探活用的文档数（D-30）：以前直接读内存镜像的 size，镜像删掉后换成一条 COUNT。
     * 放在定时任务里而不是 {@code /api/health} 上，是因为该探针被容器 healthcheck、nginx 与前端
     * 轮询打，且在改成直读之前它连 DB 往返都不做——让探活请求去等 MySQL，
     * MySQL 抖一下就把"进程能应答即 200"变成容器重启，反而是把观测变成副作用。
     * 代价是这个数最多滞后一个 TTL 周期，它是仪表读数，不是精确计数。
     */
    @Scheduled(initialDelayString = "${app.store.user-cache-ttl-seconds:60}",
            fixedDelayString = "${app.store.user-cache-ttl-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void refreshDocCount() {
        try {
            docCount = docs.count();
        } catch (Exception ex) {
            log.warn("doc count refresh failed, keep previous value {}: {}", docCount, ex.toString());
        }
    }

    /** 最近一次 TTL 刷新到的文档数（仅供探活/启动日志读数，最多滞后一个 TTL 周期） */
    public long docCount() {
        return docCount;
    }

    /** 把 settings_models 读进内存 config.models；表为空时反向把 yml 默认落库，保证界面读到的与实际一致 */
    private void loadModels() {
        Map<String, String> kv = new HashMap<>();
        for (SettingModelEntity e : settingModels.findAll()) kv.put(e.k, e.v);
        if (kv.isEmpty()) {
            // 首次启动：把 yml 中的真实模型配置落到 settings_models，保证界面读到的与实际一致
            saveModels(store.config.models);
            return;
        }
        AppConfig.ModelConfig m = store.config.models.copy();
        m.chat.baseUrl = kv.getOrDefault("chat.baseUrl", m.chat.baseUrl);
        m.chat.model = kv.getOrDefault("chat.model", m.chat.model);
        m.chat.temperature = parseD(kv.get("chat.temperature"), m.chat.temperature);
        m.embedding.model = kv.getOrDefault("embedding.model", m.embedding.model);
        store.config.models = m;
    }

    /** 同 loadModels，读写 settings_rag：表空则落默认，否则把键值解析覆盖进内存 config.rag */
    private void loadRag() {
        Map<String, String> kv = new HashMap<>();
        for (SettingRagEntity e : settingRag.findAll()) kv.put(e.k, e.v);
        if (kv.isEmpty()) {
            saveRag(store.config.rag);
            return;
        }
        AppConfig.RagConfig r = store.config.rag.copy();
        r.queryRewrite = parseB(kv.get("queryRewrite"), r.queryRewrite);
        r.multiQueryCount = (int) parseD(kv.get("multiQueryCount"), r.multiQueryCount);
        r.topK = (int) parseD(kv.get("topK"), r.topK);
        r.scoreThreshold = parseD(kv.get("scoreThreshold"), r.scoreThreshold);
        store.config.rag = r;
    }

    // ==================== 写穿 ====================

    /** 写穿 users 表并同步更新内存 users 镜像（鉴权每请求读的是这份镜像） */
    public void saveUser(User u) {
        users.save(toEntity(u));
        store.users.put(u.id, u);
    }

    /** 删 users 行并从镜像移除；两者一起做，避免镜像里残留一个库里已没有的账号 */
    public void deleteUser(String id) {
        users.deleteById(id);
        store.users.remove(id);
    }

    /** 写穿 knowledge_bases 一行（D-30 后无镜像，只落库；读走 allKbs/findKb 直查 MySQL） */
    public void saveKb(KnowledgeBase kb) {
        kbs.save(toEntity(kb));
    }

    /** 删库行；调用方（KnowledgeBaseController）先确认库内无文档才走到这里 */
    public void deleteKb(String id) {
        kbs.deleteById(id);
    }

    /** 写穿 documents 一行（新建或整体覆盖；只想更新进度用 saveDocIfPresent） */
    public void saveDoc(DocRecord d) {
        docs.save(toEntity(d));
    }

    /**
     * 解析流水线的进度写穿：行已经不在（文档被删）就别再把它写回去。
     * {@code save} 是 upsert 语义，照原对象写等于复活一篇已被删除的文档，
     * 而删除链路已经清掉它的向量和落盘文件，留下一行"READY 但什么都没有"的元数据。
     *
     * @return false 表示文档行已不存在，调用方应中止后续步骤
     */
    public boolean saveDocIfPresent(DocRecord d) {
        if (!docs.existsById(d.id)) return false;
        docs.save(toEntity(d));
        return true;
    }

    /** 删 documents 一行；向量和落盘文件由调用方另行清理，这里只管元数据 */
    public void deleteDoc(String id) {
        docs.deleteById(id);
    }

    /** 向量库对账的权威清单（D-30）：只取 id 列。查询失败会直接抛出，由调用方跳过对账 */
    public Set<String> allDocIds() {
        return new HashSet<>(docs.allIds());
    }

    /** 指定状态的文档（启动对账用，替代原先遍历整张内存镜像） */
    public List<DocRecord> docsInStatus(String... statuses) {
        List<DocRecord> out = new ArrayList<>();
        for (DocumentEntity e : docs.findByStatusIn(Arrays.asList(statuses))) out.add(toDoc(e));
        return out;
    }

    /** 删库前的护栏：某库是否仍有文档，有则拒绝删库（EXISTS 单查，不载入整库文档） */
    public boolean hasDocsInKb(String kbId) {
        return docs.existsByKbId(kbId);
    }

    /** 库内已有文档名（上传判重用，D-2）：只有 name 一列 */
    public List<String> docNamesInKb(String kbId) {
        return docs.namesInKb(kbId);
    }

    /** 知识库总数（COUNT(*)，仪表/统计读数用），非全表载入 */
    public long countKbs() {
        return kbs.count();
    }

    /** 文档总数实时 COUNT；与探活用的滞后读数 docCount() 不同，这条给需要精确值的场合 */
    public long countDocs() {
        return docs.count();
    }

    /** 某库全部文档转成领域模型（库详情/文件列表读路），按实体整行载入 */
    public List<DocRecord> docsOfKb(String kbId) {
        List<DocRecord> out = new ArrayList<>();
        for (DocumentEntity e : docs.findByKbId(kbId)) out.add(toDoc(e));
        return out;
    }

    /** 按 id 取单篇文档，转成 DocRecord 交调用方（不存在返回空 Optional） */
    public Optional<DocRecord> findDoc(String id) {
        return docs.findById(id).map(this::toDoc);
    }

    /** 全量文档（按 updatedAt 倒序），由 MySQL 直读 */
    public List<DocRecord> allDocs() {
        List<DocRecord> out = new ArrayList<>();
        for (DocumentEntity e : docs.findAllByOrderByUpdatedAtDesc()) out.add(toDoc(e));
        return out;
    }

    /** 按 id 取单个知识库（含成员列表，随实体 EAGER 载入） */
    public Optional<KnowledgeBase> findKb(String id) {
        return kbs.findById(id).map(this::toKb);
    }

    /** 全部知识库按创建时间正序（库清单/可见性判定读路，小表直查 MySQL） */
    public List<KnowledgeBase> allKbs() {
        List<KnowledgeBase> out = new ArrayList<>();
        for (KnowledgeBaseEntity e : kbs.findAllByOrderByCreatedAtAsc()) out.add(toKb(e));
        return out;
    }

    /** 其它知识库的名字（建库/改名判重用，D-2）：只有 name 一列，excludeId 传本库 id */
    public List<String> otherKbNames(String excludeId) {
        return kbs.namesOtherThan(excludeId);
    }

    /** 全部评测运行按创建时间倒序，含样本明细（整实体读，会拉 samples_json）；列表页多用轻量的 runHeadsDesc */
    public List<EvalRun> evalRunsDesc() {
        List<EvalRun> out = new ArrayList<>();
        for (EvalRunEntity e : evalRuns.findAllByOrderByCreatedAtDesc()) out.add(toEvalRun(e));
        return out;
    }

    /** 按 id 取单次运行的完整结果（metrics + samples + env） */
    public Optional<EvalRun> findEvalRun(String id) {
        return evalRuns.findById(id).map(this::toEvalRun);
    }

    /** 会话整体写穿（新增消息按 ord 追加，已有消息不重复写） */
    public void saveConversation(Conversation c) {
        ConversationEntity e = conversations.findById(c.id).orElseGet(() -> {
            ConversationEntity n = new ConversationEntity();
            n.id = c.id;
            return n;
        });
        e.userId = c.userId;
        e.title = c.title;
        e.createdAt = c.createdAt;
        e.updatedAt = c.updatedAt;
        e.knowledgeBaseIds = new ArrayList<>(c.knowledgeBaseIds == null ? List.of() : c.knowledgeBaseIds);
        Set<String> existing = new HashSet<>();
        for (MessageEntity m : e.messages) existing.add(m.id);
        int ord = e.messages.size();
        for (ChatMessage m : c.messages) {
            if (existing.contains(m.id)) continue;
            e.messages.add(toEntity(m, ord++));
        }
        conversations.save(e);
    }

    /** 删会话；消息与引用靠实体上的 cascade/orphanRemoval 一并级联删除 */
    public void deleteConversation(String id) {
        conversations.deleteById(id);
    }

    /** 按 id 载入单会话及其全部消息（转成领域模型） */
    public Optional<Conversation> findConversation(String id) {
        return conversations.findById(id).map(this::toConversation);
    }

    /** 某人的会话列表按更新时间倒序，转成领域模型给"我的会话"侧栏 */
    public List<Conversation> conversationsOf(String userId) {
        List<Conversation> out = new ArrayList<>();
        for (ConversationEntity e : conversations.findByUserIdOrderByUpdatedAtDesc(userId)) out.add(toConversation(e));
        return out;
    }

    /** 写穿 eval_runs 一行；run 的 samples/env/questionIds 是并发可变集合，先 synchronized 快照再序列化落库，failReason 截到 500 */
    public void saveEvalRun(EvalRun run) {
        EvalRunEntity e = evalRuns.findById(run.id).orElseGet(() -> {
            EvalRunEntity n = new EvalRunEntity();
            n.id = run.id;
            return n;
        });
        Map<String, Double> metrics = new LinkedHashMap<>(run.metrics);
        List<Map<String, Object>> samples;
        synchronized (run.samples) {
            samples = new ArrayList<>(run.samples);
        }
        e.createdAt = run.createdAt;
        e.status = run.status;
        e.metricsJson = Json.write(metrics);
        e.sampleCount = samples.size();
        e.samplesJson = Json.write(samples);
        e.questionSignature = run.questionSignature;
        synchronized (run.env) {
            e.envJson = Json.write(run.env);
        }
        // failReason 直接来自异常消息，长度不可控，超列宽会让整条运行记录写不进去
        e.failReason = run.failReason == null ? null
                : run.failReason.length() > 500 ? run.failReason.substring(0, 500) : run.failReason;
        synchronized (run.questionIds) {
            e.questionIds = Json.write(run.questionIds);
        }
        evalRuns.save(e);
    }

    /**
     * 把上次进程退出时仍在 running 的评测收敛成 failed（D-18）。
     * 只改 status 与 metrics_json：样本明细原样保留，
     * 既不需要（也不应该）为了改一个状态把 LONGTEXT 的样本整片读进来再写回去。
     *
     * @return 收敛掉的运行数
     */
    public int interruptRunningEvalRuns() {
        int n = 0;
        for (EvalRunEntity e : evalRuns.findByStatusOrderByCreatedAtDesc("running")) {
            Map<String, Double> metrics = parseDoubleMap(e.metricsJson, e.id);
            metrics.put("interruptedProgress", metrics.getOrDefault("progress", 0.0));
            e.status = "failed";
            e.metricsJson = Json.write(metrics);
            evalRuns.save(e);
            n++;
            log.info("中断的评测已置为 failed: run={} 进度={}", e.id, metrics.get("interruptedProgress"));
        }
        return n;
    }

    /**
     * 评测运行头部（不含样本明细，D-30）：趋势图与幻觉率告警只需要 metrics/env，
     * 按实体读会把每行的 samples_json 整片拉进 JVM——那是这张表里最大的一块。
     */
    public List<RunHead> runHeadsDesc() {
        List<RunHead> out = new ArrayList<>();
        for (Object[] row : evalRuns.runHeadsDesc()) out.add(toHead(row));
        return out;
    }

    /** 把 runHeadsDesc 的 Object[] 投影按 SELECT 列序还原成 RunHead（各 JSON 列在此解析） */
    private RunHead toHead(Object[] row) {
        RunHead h = new RunHead();
        h.id = (String) row[0];
        h.createdAt = ((Number) row[1]).longValue();
        h.status = (String) row[2];
        h.metrics = parseDoubleMap((String) row[3], h.id);
        h.questionIds = parseStringList((String) row[4], h.id);
        h.questionSignature = (String) row[5];
        h.env = parseObjectMap((String) row[6], h.id);
        h.failReason = (String) row[7];
        return h;
    }

    /** eval_runs 的轻量投影：字段口径与 {@link EvalRun} 一致，但不带 samples */
    public static final class RunHead {
        public String id;
        public long createdAt;
        public String status;
        public Map<String, Double> metrics = new LinkedHashMap<>();
        public List<String> questionIds = new ArrayList<>();
        public String questionSignature;
        public Map<String, Object> env = new LinkedHashMap<>();
        public String failReason;
    }

    /** 追加一条审计（audit_logs 只插不改，无保留期）；id/at 由传入的 AuditLog 决定 */
    public void appendAudit(AuditLog a) {
        AuditLogEntity e = new AuditLogEntity();
        e.id = a.id;
        e.actor = a.actor;
        e.action = a.action;
        e.target = a.target;
        e.detail = a.detail;
        e.at = a.at;
        auditLogs.save(e);
    }

    /** 按 limit 取最近的审计（时间倒序取页），审计镜像已删、这是唯一读路；limit 下限夹到 1 */
    public List<AuditLog> auditLogs(int limit) {
        List<AuditLog> out = new ArrayList<>();
        // 按 limit 取，不把整张审计表拉进内存再截断（D-30：审计镜像删掉后这里是唯一读路）
        for (AuditLogEntity e : auditLogs.findAllByOrderByAtDesc(PageRequest.of(0, Math.max(1, limit)))) {
            out.add(toAudit(e));
        }
        return out;
    }

    /** 直读 MySQL 取全部账号按 id 升序（管理页列表与统计在岗 SYS_ADMIN 用；区别于内存镜像 store.users） */
    public List<User> allUsers() {
        List<User> out = new ArrayList<>();
        for (UserEntity e : users.findAllByOrderByIdAsc()) out.add(toUser(e));
        return out;
    }

    /** 按登录标识查用户：先按账号（大小写不敏感），查不到再按工号兜底；登录与鉴权过滤都走它 */
    public Optional<User> userByAccount(String account) {
        Optional<UserEntity> e = users.findByAccountIgnoreCase(account);
        if (e.isEmpty()) e = users.findByEmpNoIgnoreCase(account);
        return e.map(this::toUser);
    }

    /** 按主键 id 取单个用户（转成领域模型） */
    public Optional<User> userById(String id) {
        return users.findById(id).map(this::toUser);
    }

    /** 把模型配置逐键写 settings_models（saveAll 按 k 覆盖），再同步更新内存 config.models */
    public void saveModels(AppConfig.ModelConfig m) {
        settingModels.saveAll(List.of(
                new SettingModelEntity("chat.baseUrl", m.chat.baseUrl),
                new SettingModelEntity("chat.model", m.chat.model),
                new SettingModelEntity("chat.temperature", String.valueOf(m.chat.temperature)),
                new SettingModelEntity("embedding.model", m.embedding.model)));
        store.config.models = m;
    }

    /** 把 RAG 参数逐键写 settings_rag（数值/布尔都转字符串存），再同步更新内存 config.rag（热生效） */
    public void saveRag(AppConfig.RagConfig r) {
        settingRag.saveAll(List.of(
                new SettingRagEntity("queryRewrite", String.valueOf(r.queryRewrite)),
                new SettingRagEntity("multiQueryCount", String.valueOf(r.multiQueryCount)),
                new SettingRagEntity("topK", String.valueOf(r.topK)),
                new SettingRagEntity("scoreThreshold", String.valueOf(r.scoreThreshold))));
        store.config.rag = r;
    }

    // ==================== 映射 ====================

    /** 字符串转 double：null 或解析异常都回退默认值（读 settings 表时给脏值兜底） */
    private double parseD(String s, double dflt) {
        try {
            return s == null ? dflt : Double.parseDouble(s);
        } catch (Exception e) {
            return dflt;
        }
    }

    /** 字符串转布尔的轻量版：null 回默认，其余交给 Boolean.parseBoolean（非 "true" 一律 false） */
    private boolean parseB(String s, boolean dflt) {
        return s == null ? dflt : Boolean.parseBoolean(s);
    }

    /** User → UserEntity（写穿 users 表前用；password 已是哈希，原样带过去） */
    private UserEntity toEntity(User u) {
        UserEntity e = new UserEntity();
        e.id = u.id;
        e.name = u.name;
        e.account = u.account;
        e.empNo = u.empNo;
        e.password = u.password;
        e.role = u.role;
        e.dept = u.dept;
        e.enabled = u.enabled;
        return e;
    }

    /** UserEntity → User（填鉴权镜像、读用户列表时用；逐字段浅拷，字段全为标量） */
    private User toUser(UserEntity e) {
        User u = new User();
        u.id = e.id;
        u.name = e.name;
        u.account = e.account;
        u.empNo = e.empNo;
        u.password = e.password;
        u.role = e.role;
        u.dept = e.dept;
        u.enabled = e.enabled;
        return u;
    }

    /** KnowledgeBase → KnowledgeBaseEntity（写穿库表前用；members 做防御性拷贝，null 视作空集合） */
    private KnowledgeBaseEntity toEntity(KnowledgeBase k) {
        KnowledgeBaseEntity e = new KnowledgeBaseEntity();
        e.id = k.id;
        e.name = k.name;
        e.scope = k.scope;
        e.owner = k.owner;
        e.ownerName = k.ownerName;
        e.createdAt = k.createdAt;
        e.members = new ArrayList<>(k.members == null ? List.of() : k.members);
        return e;
    }

    /** KnowledgeBaseEntity → KnowledgeBase（members 复制成新 list，交出去的可改不脏实体） */
    private KnowledgeBase toKb(KnowledgeBaseEntity e) {
        KnowledgeBase k = new KnowledgeBase();
        k.id = e.id;
        k.name = e.name;
        k.scope = e.scope;
        k.owner = e.owner;
        k.ownerName = e.ownerName;
        k.createdAt = e.createdAt;
        k.members = new ArrayList<>(e.members);
        return k;
    }

    /** DocRecord → DocumentEntity（写穿 documents 前用；含 D-30 才落库的 stage 进度阶段字段） */
    private DocumentEntity toEntity(DocRecord d) {
        DocumentEntity e = new DocumentEntity();
        e.id = d.id;
        e.name = d.name;
        e.type = d.type;
        e.kbId = d.kbId;
        e.filePath = d.filePath;
        e.status = d.status;
        e.progress = d.progress;
        e.chunkCount = d.chunkCount;
        e.failReason = d.failReason;
        e.stage = d.stage;
        e.createdAt = d.createdAt;
        e.updatedAt = d.updatedAt;
        return e;
    }

    /** DocumentEntity → DocRecord（文档读路：列表/单篇/对账都用它把实体还原成领域模型） */
    private DocRecord toDoc(DocumentEntity e) {
        DocRecord d = new DocRecord();
        d.id = e.id;
        d.name = e.name;
        d.type = e.type;
        d.kbId = e.kbId;
        d.filePath = e.filePath;
        d.status = e.status;
        d.progress = e.progress;
        d.chunkCount = e.chunkCount;
        d.failReason = e.failReason;
        d.stage = e.stage;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        return d;
    }

    /** ChatMessage → MessageEntity：ord 定会话内顺序，把 citations 的 map 逐条转成 CitationEntity（缺 id 时按序号补） */
    private MessageEntity toEntity(ChatMessage m, int ord) {
        MessageEntity e = new MessageEntity();
        e.id = m.id;
        e.role = m.role;
        e.content = m.content;
        e.createdAt = m.createdAt;
        e.ord = ord;
        if (m.citations != null) {
            int i = 0;
            for (Map<String, Object> c : m.citations) {
                CitationEntity ce = new CitationEntity();
                ce.idx = c.get("id") == null ? i + 1 : ((Number) c.get("id")).intValue();
                ce.doc = str(c.get("doc"));
                ce.docId = str(c.get("docId"));
                ce.page = c.get("page") instanceof Number n ? n.intValue() : 0;
                ce.chunkIndex = c.get("chunkIndex") instanceof Number n ? n.intValue() : null;
                ce.snippet = str(c.get("snippet"));
                ce.score = c.get("score") instanceof Number n ? n.doubleValue() : 0;
                e.citations.add(ce);
                i++;
            }
        }
        return e;
    }

    /** 引用 map 取值转字符串的小工具：null 原样返回，避免 String.valueOf 把 null 变成 "null" */
    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** MessageEntity → ChatMessage：citations 还原为 map 列表（page=0 视作无页，下发成 null 与实时路径统一） */
    private ChatMessage toMessage(MessageEntity e) {
        ChatMessage m = new ChatMessage();
        m.id = e.id;
        m.role = e.role;
        m.content = e.content;
        m.createdAt = e.createdAt;
        List<Map<String, Object>> cs = new ArrayList<>();
        for (CitationEntity c : e.citations) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", c.idx);
            mm.put("doc", c.doc);
            mm.put("docId", c.docId);
            // 0 是"无页边界"的内部表示，不能当页码下发：与实时路径（SynthesisPrompt 给 null）统一成"字段缺席"
            mm.put("page", c.page > 0 ? c.page : null);
            mm.put("chunkIndex", c.chunkIndex);
            mm.put("snippet", c.snippet);
            mm.put("score", c.score);
            cs.add(mm);
        }
        m.citations = cs;
        return m;
    }

    /** ConversationEntity → Conversation：含其全部消息（消息已按 ord EAGER 载入），逐条走 toMessage */
    private Conversation toConversation(ConversationEntity e) {
        Conversation c = new Conversation();
        c.id = e.id;
        c.userId = e.userId;
        c.title = e.title;
        c.createdAt = e.createdAt;
        c.updatedAt = e.updatedAt;
        c.knowledgeBaseIds = new ArrayList<>(e.knowledgeBaseIds);
        for (MessageEntity m : e.messages) c.messages.add(toMessage(m));
        return c;
    }

    /** EvalRunEntity → EvalRun：把 metrics/samples/env/question_ids 四段 JSON 全解析回读（整实体，含样本明细） */
    private EvalRun toEvalRun(EvalRunEntity e) {
        EvalRun r = new EvalRun();
        r.id = e.id;
        r.createdAt = e.createdAt;
        r.status = e.status;
        r.metrics.putAll(parseDoubleMap(e.metricsJson, e.id));
        r.samples.addAll(parseSampleList(e.samplesJson, e.id));
        r.questionSignature = e.questionSignature;
        r.failReason = e.failReason;
        r.env.putAll(parseObjectMap(e.envJson, e.id));
        r.questionIds.addAll(parseStringList(e.questionIds, e.id));
        return r;
    }

    /** 明细列读不出来时给空集合而不是抛：一行 JSON 写坏不该让整张列表 500 */
    private Map<String, Double> parseDoubleMap(String json, String ctx) {
        try {
            if (json == null) return new LinkedHashMap<>();
            return new LinkedHashMap<>(Json.MAPPER.readValue(json,
                    new TypeReference<LinkedHashMap<String, Double>>() {
                    }));
        } catch (Exception ex) {
            log.warn("eval run {} metrics json parse failed: {}", ctx, ex.toString());
            return new LinkedHashMap<>();
        }
    }

    /** env_json → Map<String,Object>，保序解析；null 或坏 JSON 返回空 map 并记 warn，不让一行拖垮列表 */
    private Map<String, Object> parseObjectMap(String json, String ctx) {
        try {
            if (json == null) return new LinkedHashMap<>();
            return new LinkedHashMap<>(Json.MAPPER.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }));
        } catch (Exception ex) {
            log.warn("eval run {} env json parse failed: {}", ctx, ex.toString());
            return new LinkedHashMap<>();
        }
    }

    /** question_ids JSON → List<String>；null 或坏 JSON 返回空 list 并记 warn（ctx 传运行 id 便于定位） */
    private List<String> parseStringList(String json, String ctx) {
        try {
            if (json == null) return new ArrayList<>();
            return new ArrayList<>(Json.MAPPER.readValue(json, new TypeReference<List<String>>() {
            }));
        } catch (Exception ex) {
            log.warn("eval run {} question ids parse failed: {}", ctx, ex.toString());
            return new ArrayList<>();
        }
    }

    /** samples_json → List<Map>（逐题样本明细）；解析失败返回空 list 并记 warn，与其余 parse* 口径一致 */
    private List<Map<String, Object>> parseSampleList(String json, String ctx) {
        try {
            if (json == null) return new ArrayList<>();
            return new ArrayList<>(Json.MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    }));
        } catch (Exception ex) {
            log.warn("eval run {} samples json parse failed: {}", ctx, ex.toString());
            return new ArrayList<>();
        }
    }

    /** AuditLogEntity → AuditLog：actor/action/target/detail 走构造器，id/at 因不在构造参数里再补 */
    private AuditLog toAudit(AuditLogEntity e) {
        AuditLog a = new AuditLog(e.actor, e.action, e.target, e.detail);
        a.id = e.id;
        a.at = e.at;
        return a;
    }
}
