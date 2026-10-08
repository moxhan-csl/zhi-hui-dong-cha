package com.zhihu.dongcha.settings;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.llm.FallbackLlmProvider;
import com.zhihu.dongcha.model.AppConfig;
import com.zhihu.dongcha.model.AuditLog;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.security.CurrentUser;
import com.zhihu.dongcha.security.Passwords;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 系统设置（SYS_ADMIN）：models/rag 热生效、用户 CRUD、审计日志。
 * 配置写 MySQL settings_models / settings_rag 两张键值表并即时应用到内存镜像与 LLM 客户端；
 * 用户写 MySQL users 表并同步鉴权镜像。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final List<String> ROLES = List.of("EMPLOYEE", "KM_ADMIN", "SYS_ADMIN");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9_-]{4,50}$");

    private final DbStore db;
    private final FallbackLlmProvider llm;

    /** 注入 DbStore（配置/用户/审计读写）与 FallbackLlmProvider（改配置后即时应用） */
    public SettingsController(DbStore db, FallbackLlmProvider llm) {
        this.db = db;
        this.llm = llm;
    }

    // ---------- 模型配置 ----------

    /** 读取当前 chat/embedding 模型配置（与 put 返回同结构） */
    @GetMapping("/models")
    public Mono<Map<String, Object>> getModels() {
        return Mono.fromCallable(this::modelsView).subscribeOn(Schedulers.boundedElastic());
    }

    /** 局部更新模型配置：只覆盖请求里出现的键、temperature 限 0-2，落 MySQL 后即时应用到 LLM 客户端并记审计 */
    @PutMapping("/models")
    public Mono<Map<String, Object>> putModels(@RequestBody Map<String, Object> body, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            AppConfig.ModelConfig next = db.config().models.copy();
            if (body.get("chat") instanceof Map<?, ?> chat) {
                if (chat.get("baseUrl") != null) next.chat.baseUrl = String.valueOf(chat.get("baseUrl"));
                if (chat.get("model") != null) next.chat.model = String.valueOf(chat.get("model"));
                if (chat.get("temperature") != null) {
                    double t = Double.parseDouble(String.valueOf(chat.get("temperature")));
                    if (t < 0 || t > 2) throw BizException.badRequest("temperature 需为 0-2");
                    next.chat.temperature = t;
                }
            }
            if (body.get("embedding") instanceof Map<?, ?> emb && emb.get("model") != null) {
                next.embedding.model = String.valueOf(emb.get("model"));
            }
            db.saveModels(next);                                        // MySQL settings_models
            llm.applyConfig(next.chat.baseUrl, next.chat.model, next.embedding.model);  // 即时生效
            db.appendAudit(new AuditLog(actor.account, "MODEL_CONFIG_UPDATE", "models",
                    "chat=" + next.chat.model + "@" + next.chat.baseUrl + ", embedding=" + next.embedding.model));
            return modelsView();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 把内存模型配置拼成 {chat:{baseUrl,model,temperature}, embedding:{model}} 的响应体 */
    private Map<String, Object> modelsView() {
        var mc = db.config().models;
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("baseUrl", mc.chat.baseUrl);
        chat.put("model", mc.chat.model);
        chat.put("temperature", mc.chat.temperature);
        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("model", mc.embedding.model);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chat", chat);
        m.put("embedding", emb);
        return m;
    }

    // ---------- RAG 策略（热生效：检索链路实时读取镜像配置） ----------

    /** 读取当前 RAG 策略参数（queryRewrite/multiQueryCount/topK/scoreThreshold） */
    @GetMapping("/rag")
    public Mono<Map<String, Object>> getRag() {
        return Mono.fromCallable(this::ragView).subscribeOn(Schedulers.boundedElastic());
    }

    /** 局部更新 RAG 参数并校验区间（multiQuery 1-5、topK 1-20、threshold 0-1），落 MySQL 后热生效并记审计 */
    @PutMapping("/rag")
    public Mono<Map<String, Object>> putRag(@RequestBody Map<String, Object> body, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            AppConfig.RagConfig next = db.config().rag.copy();
            if (body.get("queryRewrite") != null) next.queryRewrite = Boolean.parseBoolean(String.valueOf(body.get("queryRewrite")));
            if (body.get("multiQueryCount") != null) {
                int n = Integer.parseInt(String.valueOf(body.get("multiQueryCount")));
                if (n < 1 || n > 5) throw BizException.badRequest("multiQueryCount 需为 1-5");
                next.multiQueryCount = n;
            }
            if (body.get("topK") != null) {
                int k = Integer.parseInt(String.valueOf(body.get("topK")));
                if (k < 1 || k > 20) throw BizException.badRequest("topK 需为 1-20");
                next.topK = k;
            }
            if (body.get("scoreThreshold") != null) {
                double t = Double.parseDouble(String.valueOf(body.get("scoreThreshold")));
                if (t < 0 || t > 1) throw BizException.badRequest("scoreThreshold 需为 0-1");
                next.scoreThreshold = t;
            }
            db.saveRag(next);         // MySQL settings_rag
            db.appendAudit(new AuditLog(actor.account, "RAG_CONFIG_UPDATE", "rag",
                    String.format("queryRewrite=%s, multiQuery=%d, topK=%d, threshold=%.2f",
                            next.queryRewrite, next.multiQueryCount, next.topK, next.scoreThreshold)));
            return ragView();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 把内存 RAG 配置拼成含四个参数键的扁平响应体 */
    private Map<String, Object> ragView() {
        var r = db.config().rag;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queryRewrite", r.queryRewrite);
        m.put("multiQueryCount", r.multiQueryCount);
        m.put("topK", r.topK);
        m.put("scoreThreshold", r.scoreThreshold);
        return m;
    }

    // ---------- 用户管理 ----------

    /** 列出全部用户：按 account 排序，经 User.view 输出（不含密码） */
    @GetMapping("/users")
    public Mono<List<Map<String, Object>>> users() {
        return Mono.fromCallable(() -> {
            List<User> list = new ArrayList<>(db.allUsers());
            list.sort(Comparator.comparing(u -> u.account));
            return list.stream().map(User::view).toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 用户增改请求体；enabled 用包装类型 Boolean，以区分"未传该字段"与"显式设为 false" */
    public record UserRequest(String name, String account, String empNo, String password, String role,
                              String dept, Boolean enabled) {
    }

    /** 新建用户：校验账号(邮箱或工号)、姓名、密码 6-32、角色合法并做登录标识撞车检查，密码哈希后存库并记审计 */
    @PostMapping("/users")
    public Mono<Map<String, Object>> createUser(@RequestBody UserRequest req, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            String account = req.account() == null ? "" : req.account().trim();
            if (account.isEmpty() || account.length() > 50
                    || !(EMAIL.matcher(account).matches() || EMP_NO.matcher(account).matches()))
                throw BizException.badRequest("账号需为邮箱或工号（4-50 位）");
            if (req.name() == null || req.name().isBlank()) throw BizException.badRequest("姓名不能为空");
            if (req.password() == null || req.password().length() < 6 || req.password().length() > 32)
                throw BizException.badRequest("密码需为 6-32 位");
            if (req.role() == null || !ROLES.contains(req.role())) throw BizException.badRequest("role 非法");
            String empNo = req.empNo() == null || req.empNo().isBlank() ? null : req.empNo().trim();
            requireDistinctIdentity(account, empNo, null);
            User u = new User(req.name().trim(), account, empNo, Passwords.hash(req.password()), req.role(),
                    req.dept() == null ? "综合管理部" : req.dept());
            db.saveUser(u);
            db.appendAudit(new AuditLog(actor.account, "USER_CREATE", u.account, "role=" + u.role));
            return u.view();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 改用户：只更新请求里出现的字段。含自我保护（不能自降级/自停用）与"最后一名启用管理员"保护；改 empNo 才做撞车校验，改密/启停分别记审计 */
    @PutMapping("/users/{id}")
    public Mono<Map<String, Object>> updateUser(@PathVariable String id, @RequestBody UserRequest req,
                                                ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            User u = db.userById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
            if (req.role() != null && !ROLES.contains(req.role())) throw BizException.badRequest("role 非法");
            boolean self = u.id.equals(actor.id);
            boolean demoting = req.role() != null && !req.role().equals(u.role)
                    && !"SYS_ADMIN".equals(req.role());
            boolean disabling = req.enabled() != null && !req.enabled();
            // 这类操作会把操作者自己（或系统里最后一个管理员）关在门外，事后只能改库恢复
            if (self && (demoting || disabling)) {
                throw BizException.badRequest(demoting
                        ? "不能修改自己的角色。需要移交管理员权限，请用另一个系统管理员账号操作本页面"
                        : "不能停用当前登录账号");
            }
            if ((demoting || disabling) && "SYS_ADMIN".equals(u.role) && activeAdminCount() <= 1) {
                throw BizException.badRequest("系统里只剩这一名启用中的系统管理员，不能再降级或停用");
            }
            StringBuilder audit = new StringBuilder();
            if (req.role() != null && !req.role().equals(u.role)) {
                String old = u.role;
                u.role = req.role();
                audit.append("role ").append(old).append(" → ").append(u.role);
            }
            if (req.name() != null && !req.name().isBlank()) u.name = req.name().trim();
            if (req.dept() != null && !req.dept().isBlank()) u.dept = req.dept();
            if (req.empNo() != null) {
                // 空串按"清空工号"处理：留一堆 "" 会让"按工号登录"那条查询命中多行
                String next = req.empNo().isBlank() ? null : req.empNo().trim();
                if (!Objects.equals(next, u.empNo)) {
                    // 只校验真正要写进去的新值：存量里已有的撞车不该让这个用户的其它字段也改不动。
                    // 这里刻意不加格式校验——建号时本来就没管过工号格式，这一批改的只是"撞车"这一件事
                    requireDistinctIdentity(null, next, u.id);
                    u.empNo = next;
                }
            }
            if (req.password() != null && !req.password().isBlank()) {
                if (req.password().length() < 6 || req.password().length() > 32)
                    throw BizException.badRequest("密码需为 6-32 位");
                u.password = Passwords.hash(req.password());
            }
            if (req.enabled() != null) u.enabled = req.enabled();
            db.saveUser(u);
            if (audit.length() > 0) {
                db.appendAudit(new AuditLog(actor.account, "USER_ROLE_CHANGE", u.account, audit.toString()));
            }
            if (req.enabled() != null) {
                db.appendAudit(new AuditLog(actor.account, "USER_ENABLED_CHANGE", u.account, "enabled=" + u.enabled));
            }
            return u.view();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 删除用户：拒绝删除当前登录账号与内置超管 admin@corp.com，删后记审计并回执 {deleted,id} */
    @DeleteMapping("/users/{id}")
    public Mono<Map<String, Object>> deleteUser(@PathVariable String id, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
            User u = db.userById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
            if (u.id.equals(actor.id)) throw BizException.badRequest("不能删除当前登录账号");
            if ("admin@corp.com".equalsIgnoreCase(u.account)) throw BizException.badRequest("内置超管账号不可删除");
            db.deleteUser(id);
            db.appendAudit(new AuditLog(actor.account, "USER_DELETE", u.account, "删除用户"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", true);
            out.put("id", id);
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ---------- 审计日志 ----------

    /**
     * 登录标识唯一性（D-2，代码层判定）。account 与 emp_no 都能登录
     * （{@link com.zhihu.dongcha.db.DbStore#userByAccount} 先查账号、查不到再查工号），
     * 所以任意两个值撞车都会出事：工号与工号重复时，按工号登录的那条查询会命中两行、
     * Spring Data 的 {@code Optional} 返回直接抛 NonUniqueResultException（登录 500）；
     * 账号与别人的工号重复时，那个工号永远解析到账号持有者，另一个人无声地登不进来。
     * <p>不给 emp_no 加 UNIQUE：共享生产实例上存量数据可能已经脏，约束一上写入即失败，
     * 而且这是业务语义不是数据完整性。代价如实说：读后写、不原子，并发创建仍可能双双通过；
     * 已有的撞车也不会被这次改动自动发现——核对办法见 DEPLOY.md 的发版后检查清单。
     *
     * @param selfId 更新时传本人 id，避免"没改工号却被自己挡住"
     */
    private void requireDistinctIdentity(String account, String empNo, String selfId) {
        for (User x : db.allUsers()) {
            if (selfId != null && selfId.equals(x.id)) continue;
            boolean accountClash = eqI(x.account, account) || eqI(x.empNo, account);
            boolean empNoClash = eqI(x.account, empNo) || eqI(x.empNo, empNo);
            if (accountClash || empNoClash) {
                throw BizException.badRequest("账号或工号与其它用户重复：" + (accountClash ? account : empNo)
                        + " 已经是「" + x.name + "（" + x.account + "）」的登录标识。"
                        + "账号与工号都能登录，撞车会让其中一个人登不上系统");
            }
        }
    }

    /** 忽略大小写比较两值；任一为 null 直接判不等 */
    private static boolean eqI(String stored, String candidate) {
        return stored != null && candidate != null && stored.equalsIgnoreCase(candidate);
    }

    /** 启用中的系统管理员数量：降到 0 就没人能进系统设置，等于全站失管 */
    private long activeAdminCount() {
        return db.allUsers().stream()
                .filter(x -> "SYS_ADMIN".equals(x.role) && x.enabled)
                .count();
    }

    /** 最近审计日志：limit 钳到 1-500 后走一次有界倒序查询，逐条导出为 Map */
    @GetMapping("/audit-logs")
    public Mono<List<Map<String, Object>>> auditLogs(@RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> {
            // D-10：limit 不钳制就等于"一次要多少给多少"，钳到 500 让它落成一次有界分页查询
            // D-30 之后审计镜像已删，这里走 `order by at_time desc limit n`（at_time 的索引属 D-2 第九批）
            int capped = Math.min(Math.max(limit, 1), 500);
            List<Map<String, Object>> out = new ArrayList<>();
            for (AuditLog a : db.auditLogs(capped)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.id);
                m.put("actor", a.actor);
                m.put("action", a.action);
                m.put("target", a.target);
                m.put("detail", a.detail);
                m.put("at", a.at);
                out.add(m);
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
