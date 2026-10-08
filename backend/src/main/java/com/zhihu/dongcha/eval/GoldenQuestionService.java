package com.zhihu.dongcha.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.db.GoldenQuestionEntity;
import com.zhihu.dongcha.db.GoldenQuestionRepository;
import com.zhihu.dongcha.model.AuditLog;
import com.zhihu.dongcha.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 评测题库：MySQL golden_questions 表为唯一真相（不再读 jar 里的 golden_set.jsonl）。
 * <p>核心约束是<b>只有 reviewed 的题目参与评测计分</b>：题目内容一改就退回 draft，
 * 必须重新复核。否则"考卷被改过但趋势还在画旧分数"这种自证清白的指标又会回来。
 * <p>方法均为阻塞 JPA，由 Controller 包在 boundedElastic 上执行。
 */
@Service
public class GoldenQuestionService {

    private static final Logger log = LoggerFactory.getLogger(GoldenQuestionService.class);
    private static final int MAX_EXPECTED_DOC = 255;

    /** 一道考题在评测流水线里的最小视图 */
    public record Golden(String id, String question, String golden, String expectedDoc) {
    }

    /** 新建/编辑入参：题干与期望答案必填；期望文档可暂空，但复核通过前必须补上。 */
    public record QuestionInput(String question, String golden, String expectedDoc, String note, Boolean enabled) {
    }

    private final GoldenQuestionRepository repo;
    private final DbStore db;

    public GoldenQuestionService(GoldenQuestionRepository repo, DbStore db) {
        this.repo = repo;
        this.db = db;
    }

    // ==================== 查询 ====================

    /** 列表：status 恰为 draft 或 reviewed 时按状态过滤，其余取值返回全部，按更新时间倒序。 */
    public List<Map<String, Object>> list(String status) {
        List<GoldenQuestionEntity> all = "draft".equals(status) || "reviewed".equals(status)
                ? repo.findByStatusOrderByUpdatedAtDesc(status)
                : repo.findAllByOrderByUpdatedAtDesc();
        return all.stream().map(this::view).toList();
    }

    /** 题库实况：前端据此判断"能跑几条"，不把没有的东西画成 0 分 */
    public Map<String, Object> stats() {
        List<GoldenQuestionEntity> all = repo.findAllByOrderByUpdatedAtDesc();
        long reviewed = all.stream().filter(e -> "reviewed".equals(e.status)).count();
        long usable = all.stream().filter(e -> "reviewed".equals(e.status) && e.enabled).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("draft", all.size() - reviewed);
        m.put("reviewed", reviewed);
        m.put("usable", usable);   // 参与评测的条数：reviewed 且 enabled
        m.put("storage", "mysql:golden_questions");
        return m;
    }

    // ==================== 写入 ====================

    /** 新建考题：状态强制 draft、enabled 不传默认启用；写 GOLDEN_CREATE 审计。 */
    @Transactional
    public Map<String, Object> create(QuestionInput in, User actor) {
        GoldenQuestionEntity e = new GoldenQuestionEntity();
        e.id = UUID.randomUUID().toString();
        e.createdAt = System.currentTimeMillis();
        e.createdBy = actor.account;
        e.source = "manual";
        applyContent(e, in);
        requireUniqueQuestion(e.question, "");
        e.status = "draft";
        e.enabled = in.enabled() == null || in.enabled();
        e.updatedAt = e.createdAt;
        repo.save(e);
        db.appendAudit(new AuditLog(actor.account, "GOLDEN_CREATE", brief(e.question), "新建考题（草稿）"));
        return view(e);
    }

    /** 修改：题面/答案/期望文档任一实质变化即退回 draft 并清复核人；题干归一化有变才重判重复；写 GOLDEN_UPDATE 审计。 */
    @Transactional
    public Map<String, Object> update(String id, QuestionInput in, User actor) {
        GoldenQuestionEntity e = require(id);
        String prevQuestion = normalize(e.question);
        boolean contentChanged = !Objects.equals(nz(e.question), nz(in.question()))
                || !Objects.equals(nz(e.golden), nz(in.golden()))
                || !Objects.equals(nz(e.expectedDoc), nz(in.expectedDoc()));
        applyContent(e, in);
        if (!prevQuestion.equals(normalize(e.question))) requireUniqueQuestion(e.question, e.id);
        if (in.enabled() != null) e.enabled = in.enabled();
        // 改过题面/答案/期望文档就要重新复核，旧复核结论对新内容没有效力
        if (contentChanged && "reviewed".equals(e.status)) {
            e.status = "draft";
            e.reviewedBy = null;
            e.reviewedAt = 0;
        }
        e.updatedAt = System.currentTimeMillis();
        repo.save(e);
        db.appendAudit(new AuditLog(actor.account, "GOLDEN_UPDATE", brief(e.question),
                contentChanged ? "修改考题" + (e.status.equals("draft") ? "，已退回待复核" : "") : "修改考题备注"));
        return view(e);
    }

    /** 单题复核：通过要求期望文档非空（否则无法判检索命中），退回时清空复核人与时间；写复核类审计。 */
    @Transactional
    public Map<String, Object> setReviewed(String id, boolean reviewed, User actor) {
        GoldenQuestionEntity e = require(id);
        if (reviewed && blank(e.expectedDoc)) throw BizException.badRequest("期望文档为空，不能标记为已复核");
        e.status = reviewed ? "reviewed" : "draft";
        e.reviewedBy = reviewed ? actor.account : null;
        e.reviewedAt = reviewed ? System.currentTimeMillis() : 0;
        e.updatedAt = System.currentTimeMillis();
        repo.save(e);
        db.appendAudit(new AuditLog(actor.account, reviewed ? "GOLDEN_REVIEW" : "GOLDEN_UNREVIEW",
                brief(e.question), reviewed ? "确认考题可用" : "退回待复核"));
        return view(e);
    }

    /** 批量复核：ids 保序去重后逐条走 setReviewed，每题各写一条审计。 */
    @Transactional
    public Map<String, Object> setReviewedBatch(List<String> ids, boolean reviewed, User actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : dedupe(ids)) out.add(setReviewed(id, reviewed, actor));
        return Map.of("count", out.size());
    }

    /** 启停开关：只影响该题能否被评测选用，不动复核状态，也不写审计。 */
    @Transactional
    public Map<String, Object> setEnabled(String id, boolean enabled) {
        GoldenQuestionEntity e = require(id);
        e.enabled = enabled;
        e.updatedAt = System.currentTimeMillis();
        repo.save(e);
        return view(e);
    }

    /** 批量删除：ids 去重后从 MySQL 物理删除（不可恢复），空选择直接报错；写 GOLDEN_DELETE 审计。 */
    @Transactional
    public Map<String, Object> delete(List<String> ids, User actor) {
        List<String> unique = dedupe(ids);
        if (unique.isEmpty()) throw BizException.badRequest("未选择要删除的题目");
        repo.deleteAllById(unique);
        db.appendAudit(new AuditLog(actor.account, "GOLDEN_DELETE", unique.size() + " 题", "删除考题"));
        return Map.of("deleted", unique.size());
    }

    // ==================== 导入导出 ====================

    /**
     * 导入 JSONL（每行 {question, golden, expectedDoc, note?}）。
     * 一律按 draft 入库、按题干去重——导入的题目不能自带"已复核"身份。
     */
    @Transactional
    public Map<String, Object> importJsonl(String text, User actor) {
        if (blank(text)) throw BizException.badRequest("导入内容为空");
        Set<String> known = new HashSet<>();
        // 只取题干一列：导入去重不需要 golden / note，把整张表连 TEXT 拉进 JVM 是白付的钱
        for (String q : repo.questionsOtherThan("")) known.add(normalize(q));
        int created = 0, skipped = 0, bad = 0;
        long now = System.currentTimeMillis();
        List<GoldenQuestionEntity> batch = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode n;
            try {
                n = Json.MAPPER.readTree(line);
            } catch (Exception ex) {
                bad++;
                continue;
            }
            String question = n.path("question").asText("");
            if (question.isBlank()) {
                bad++;
                continue;
            }
            if (!known.add(normalize(question))) {
                skipped++;
                continue;
            }
            GoldenQuestionEntity e = new GoldenQuestionEntity();
            e.id = UUID.randomUUID().toString();
            e.question = question.trim();
            e.golden = n.path("golden").asText("");
            e.expectedDoc = n.path("expectedDoc").asText("");
            e.note = n.path("note").asText("");
            e.source = "imported";
            e.status = "draft";
            e.enabled = true;
            e.createdBy = actor.account;
            e.createdAt = now;
            e.updatedAt = now;
            batch.add(e);
            created++;
        }
        if (batch.isEmpty()) {
            throw BizException.badRequest("没有可导入的题目（去重跳过 " + skipped + " 条，格式不符 " + bad + " 条）");
        }
        repo.saveAll(batch);
        db.appendAudit(new AuditLog(actor.account, "GOLDEN_IMPORT", created + " 题",
                "导入考题，全部按草稿入库"));
        log.info("golden import by {}: created={} skipped={} bad={}", actor.account, created, skipped, bad);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", created);
        m.put("skippedDuplicate", skipped);
        m.put("skippedInvalid", bad);
        m.put("allAsDraft", true);
        return m;
    }

    /** 全量题库导出为 JSONL（含草稿与未复核题），按创建时间正序；只读不写审计。 */
    public String exportJsonl() {
        StringBuilder sb = new StringBuilder();
        for (GoldenQuestionEntity e : repo.findAllByOrderByCreatedAtAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id);
            row.put("question", e.question);
            row.put("golden", e.golden);
            row.put("expectedDoc", e.expectedDoc);
            row.put("note", e.note);
            row.put("status", e.status);
            row.put("source", e.source);
            try {
                sb.append(Json.MAPPER.writeValueAsString(row)).append("\n");
            } catch (Exception ex) {
                throw BizException.badRequest("导出失败：题目内容无法序列化");
            }
        }
        return sb.toString();
    }

    // ==================== 供评测取题 ====================

    /** ids 为空 = 全部"已复核且启用"；显式指定 ids 时逐条校验复核状态，未复核的直接拒绝 */
    public List<Golden> pickForRun(List<String> ids) {
        List<String> unique = dedupe(ids);
        if (unique.isEmpty()) {
            return repo.findAllByOrderByCreatedAtAsc().stream()
                    .filter(e -> "reviewed".equals(e.status) && e.enabled)
                    .map(this::toGolden)
                    .toList();
        }
        List<Golden> out = new ArrayList<>();
        List<String> unreviewed = new ArrayList<>();
        for (String id : unique) {
            GoldenQuestionEntity e = repo.findById(id).orElseThrow(() -> BizException.notFound("考题不存在: " + id));
            if (!"reviewed".equals(e.status)) {
                unreviewed.add(brief(e.question));
                continue;
            }
            if (!e.enabled) continue;   // 显式勾了但被停用：跳过而不是报错
            out.add(toGolden(e));
        }
        if (!unreviewed.isEmpty()) {
            throw BizException.badRequest("勾选的题目里有 " + unreviewed.size() + " 条尚未人工复核，不参与计分。"
                    + "先在题库页复核，或改为使用全部已复核题目：" + String.join("、", unreviewed.subList(0, Math.min(3, unreviewed.size()))));
        }
        return out;
    }

    /** 题集指纹：同一套题的两次运行才可比，题目集合一变趋势线就该重新起一条 */
    public static String signature(List<Golden> samples) {
        List<String> ids = new ArrayList<>(samples.stream().map(Golden::id).toList());
        Collections.sort(ids);
        return sha1(String.join(",", ids)) + ":" + ids.size();
    }

    // ==================== 内部 ====================

    /**
     * 题干判重（D-2）：与导入用同一条 {@link #normalize} 口径（折叠空白、忽略大小写）。
     * <p>为什么要拒：评测是按题平均的，同一道题在库里存在两次就占两份权重，
     * 而题集指纹只统计 id 集合，看不出"这两条其实是同一个问题"——分数会失真且不留痕迹。
     * <p>与其余业务判重一样只在代码层做（共享生产库不加 UNIQUE）；读后写不原子，
     * 并发新建同题干仍可能双双通过，事后删掉多余一条即可纠正。
     */
    private void requireUniqueQuestion(String question, String excludeId) {
        String key = normalize(question);
        if (key.isEmpty()) return;
        for (String q : repo.questionsOtherThan(excludeId)) {
            if (normalize(q).equals(key)) {
                throw BizException.badRequest("题库里已有同样的题干：" + brief(question)
                        + "。重复的题目会在计分里占两份权重，请删除多余的那条或改写题干");
            }
        }
    }

    /** 入参校验并 trim 落字段：题干/期望答案必填，期望文档名限长 255；题干判重由调用方另做。 */
    private void applyContent(GoldenQuestionEntity e, QuestionInput in) {
        if (in == null) throw BizException.badRequest("题目内容为空");
        if (blank(in.question())) throw BizException.badRequest("题干不能为空");
        if (blank(in.golden())) throw BizException.badRequest("期望答案要点不能为空");
        if (in.expectedDoc() != null && in.expectedDoc().length() > MAX_EXPECTED_DOC) {
            throw BizException.badRequest("期望文档名最长 " + MAX_EXPECTED_DOC + " 字符");
        }
        e.question = in.question().trim();
        e.golden = in.golden().trim();
        e.expectedDoc = in.expectedDoc() == null ? "" : in.expectedDoc().trim();
        if (in.note() != null) e.note = in.note();
    }

    private GoldenQuestionEntity require(String id) {
        return repo.findById(id).orElseThrow(() -> BizException.notFound("考题不存在"));
    }

    private Golden toGolden(GoldenQuestionEntity e) {
        return new Golden(e.id, e.question, e.golden, e.expectedDoc);
    }

    private Map<String, Object> view(GoldenQuestionEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("question", e.question);
        m.put("golden", e.golden);
        m.put("expectedDoc", e.expectedDoc);
        m.put("source", e.source);
        m.put("status", e.status);
        m.put("enabled", e.enabled);
        m.put("note", e.note);
        m.put("createdBy", e.createdBy);
        m.put("reviewedBy", e.reviewedBy);
        m.put("createdAt", e.createdAt);
        m.put("updatedAt", e.updatedAt);
        m.put("reviewedAt", e.reviewedAt);
        return m;
    }

    private static List<String> dedupe(List<String> ids) {
        if (ids == null) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 内容变化检测用的比较口径：null 视作空、trim 后再比，首尾空白改动不触发退回复核。 */
    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 题干判重口径：去首尾空白、折叠连续空白、转小写；判重与导入去重共用这一条。 */
    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 题干截断到 40 字，供审计条目和报错消息展示，避免整段题目进日志。 */
    private static String brief(String q) {
        if (q == null) return "";
        return q.length() > 40 ? q.substring(0, 40) + "…" : q;
    }

    /** 取 SHA-1 摘要前 8 字节的十六进制作指纹值；算法不可用时退化为 hashCode 十六进制，不抛异常。 */
    private static String sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
