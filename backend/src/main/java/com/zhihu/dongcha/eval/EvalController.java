package com.zhihu.dongcha.eval;

import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 评测中心（KM_ADMIN+，角色由 AuthWebFilter 拦截）。
 * 题库读写是阻塞 JPA，一律搬到 boundedElastic 上执行；评测任务本身在 eval-worker 线程异步跑。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final GoldenQuestionService questions;

    public EvalController(EvalService evalService, GoldenQuestionService questions) {
        this.evalService = evalService;
        this.questions = questions;
    }

    /** 评测发起请求：questionIds 为空或不传时跑全部「已复核且启用」的题目。 */
    public record RunRequest(List<String> questionIds) {
    }

    /** 批量复核请求：reviewed 为 true 表示整批标记通过，false 表示退回草稿。 */
    public record ReviewRequest(List<String> ids, boolean reviewed) {
    }

    /** 启停请求：只切换题目是否参与评测选用，不影响复核结论。 */
    public record EnabledRequest(String id, boolean enabled) {
    }

    /** 批量操作请求：ids 为要删除的考题 ID 列表。 */
    public record IdsRequest(List<String> ids) {
    }

    /** 导入请求：text 是 JSONL 原文，每行一道题。 */
    public record ImportRequest(String text) {
    }

    /** 发起异步评测：检索按当前登录者可见库执行；跑分在 eval-worker 后台进行，接口立即返回 runId。 */
    @PostMapping("/run")
    public Mono<Map<String, String>> run(@RequestBody(required = false) RunRequest req, ServerWebExchange ex) {
        User initiator = CurrentUser.of(ex);
        return Mono.fromCallable(() -> {
                    String runId = evalService.run(initiator, req == null ? List.of() : req.questionIds());
                    return Map.of("runId", runId, "status", "running");
                }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 历史运行列表：概览视图（含指标与题集指纹），不含逐题样本明细，按时间倒序。 */
    @GetMapping("/runs")
    public Mono<List<Map<String, Object>>> runs() {
        return Mono.fromCallable(evalService::runs).subscribeOn(Schedulers.boundedElastic());
    }

    /** 单次运行完整明细：概览字段之外再带每题答案、分项得分与判定来源，ID 不存在返回 404。 */
    @GetMapping("/runs/{id}")
    public Mono<Map<String, Object>> runDetail(@PathVariable String id) {
        return Mono.fromCallable(() -> evalService.runDetail(id)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 趋势序列：仅 status=done 的运行、按时间正序，每点带指标、题集指纹与环境快照供前端判断可比性。 */
    @GetMapping("/trend")
    public Mono<List<Map<String, Object>>> trend() {
        return Mono.fromCallable(evalService::trend).subscribeOn(Schedulers.boundedElastic());
    }

    /** 题库实况：总数 / 待复核 / 已复核 / 可参与评测的条数 */
    @GetMapping("/golden-set")
    public Mono<Map<String, Object>> goldenSet() {
        return Mono.fromCallable(evalService::goldenSetInfo).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 题库管理 ====================

    /** 题目列表：status 恰为 draft 或 reviewed 时按状态过滤，其余取值返回全部，按更新时间倒序。 */
    @GetMapping("/questions")
    public Mono<List<Map<String, Object>>> listQuestions(@RequestParam(required = false) String status) {
        return Mono.fromCallable(() -> questions.list(status)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 新建考题：一律以草稿入库、不自带复核身份；写 GOLDEN_CREATE 审计。 */
    @PostMapping("/questions")
    public Mono<Map<String, Object>> createQuestion(@RequestBody GoldenQuestionService.QuestionInput in,
                                                    ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> questions.create(in, actor)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 修改考题：题面/答案/期望文档任一变化即退回待复核；写 GOLDEN_UPDATE 审计。 */
    @PutMapping("/questions/{id}")
    public Mono<Map<String, Object>> updateQuestion(@PathVariable String id,
                                                    @RequestBody GoldenQuestionService.QuestionInput in,
                                                    ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> questions.update(id, in, actor)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 复核 / 退回，单条或批量都走这里（ids 必填） */
    @PostMapping("/questions/review")
    public Mono<Map<String, Object>> reviewQuestions(@RequestBody ReviewRequest req, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> questions.setReviewedBatch(req.ids(), req.reviewed(), actor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 只切换题目启用状态（是否可被评测选用），不改复核结论、不写审计。 */
    @PostMapping("/questions/enabled")
    public Mono<Map<String, Object>> setEnabled(@RequestBody EnabledRequest req) {
        return Mono.fromCallable(() -> questions.setEnabled(req.id(), req.enabled()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量物理删除考题（按 id 去重后删，删后不可恢复）；写 GOLDEN_DELETE 审计。 */
    @DeleteMapping("/questions")
    public Mono<Map<String, Object>> deleteQuestions(@RequestBody IdsRequest req, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> questions.delete(req.ids(), actor)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 按 JSONL 批量导入考题：全部按草稿入库并按题干去重；写 GOLDEN_IMPORT 审计。 */
    @PostMapping("/questions/import")
    public Mono<Map<String, Object>> importQuestions(@RequestBody ImportRequest req, ServerWebExchange ex) {
        User actor = CurrentUser.of(ex);
        return Mono.fromCallable(() -> questions.importJsonl(req.text(), actor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 下载全量题库 JSONL（含草稿与期望答案，供备份/迁移），只读不写审计。 */
    @GetMapping("/questions/export")
    public Mono<ResponseEntity<byte[]>> exportQuestions() {
        return Mono.fromCallable(() -> {
            String jsonl = questions.exportJsonl();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=golden-questions-" + LocalDate.now() + ".jsonl")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(jsonl.getBytes(StandardCharsets.UTF_8));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 下载单次评测结果 JSONL：首行为运行头（指标+环境快照），随后每行一个样本明细。 */
    @GetMapping("/export/{id}")
    public Mono<ResponseEntity<byte[]>> export(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            String jsonl = evalService.exportJsonl(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=eval-" + id + ".jsonl")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(jsonl.getBytes(StandardCharsets.UTF_8));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
