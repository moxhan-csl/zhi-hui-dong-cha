package com.zhihu.dongcha.monitor;

import com.zhihu.dongcha.config.Availability;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.rag.DelegatingChunkStore;
import com.zhihu.dongcha.redis.CostService;
import com.zhihu.dongcha.store.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 监控中心：
 * <ul>
 *   <li>成本：当日 tokens / 金额来自 Redis（{@code cost:tokens:{yyyyMMdd}}、{@code cost:amount:{yyyyMMdd}}，
 *       由 {@link CostService} 在每次真实 LLM 调用后累计），近 7 日趋势逐日读取同一组 key，无数据即为 0；</li>
 *   <li>P50/P95 与请求量趋势：进程内实时统计（{@code MetricsWebFilter} 累加），只覆盖当前实例、当前进程；</li>
 *   <li>幻觉率告警：读 MySQL 最近一次完成的评测。</li>
 * </ul>
 * 服务可用性与 Pod 健康数不在这里报：没有探活体系支撑时它们只能是常量，展示出来会被误读成测量结果。
 * 方法均为阻塞（Redis + JPA），由 Controller 包在 boundedElastic 上执行。
 */
@Service
public class MonitorService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final Store store;
    private final DbStore db;
    private final CostService cost;
    private final Availability availability;
    private final DelegatingChunkStore chunkStore;
    /** 成本告警阈值：配置项 app.monitor.cost-threshold，写死在代码里却展示成"预算"会被当成真实约束 */
    private final double costThreshold;

    public MonitorService(Store store, DbStore db, CostService cost,
                          Availability availability, DelegatingChunkStore chunkStore,
                          @Value("${app.monitor.cost-threshold}") double costThreshold) {
        this.store = store;
        this.db = db;
        this.cost = cost;
        this.availability = availability;
        this.chunkStore = chunkStore;
        this.costThreshold = costThreshold;
    }

    /** 一次监控快照：P50/P95 与样本数、成本块、近30分钟请求趋势，并用同批数值现算告警列表一并返回。*/
    public Map<String, Object> metrics() {
        List<Long> latencies = store.latenciesSnapshot();
        Double p50 = percentile(latencies, 0.50);
        Double p95 = percentile(latencies, 0.95);

        Map<String, Object> m = new LinkedHashMap<>();
        // 样本为空时分位数是"未测得"而不是 0ms：这里给 null，配合 non_null 序列化即整个字段缺席，
        // 前端/脚本读不到就当没有测过（D-19，与设计文档 §4.9 的第 1 条口径一致）
        m.put("p50Ms", p50 == null ? null : round1(p50));
        m.put("p95Ms", p95 == null ? null : round1(p95));
        m.put("latencySamples", latencies.size());
        Map<String, Object> costBlock = cost();
        m.put("cost", costBlock);
        m.put("requestTrend", requestTrend());
        m.put("alerts", alerts(p95, ((Number) costBlock.get("today")).doubleValue()));
        m.put("sources", Map.of("cost", cost.sourceName(), "latency", "in-process-ring-buffer"));
        return m;
    }

    /** 独立查询入口：现取 P95 延迟与当日金额后复用私有 alerts(p95,costToday) 计算。*/
    public List<Map<String, Object>> alerts() {
        return alerts(percentile(store.latenciesSnapshot(), 0.95), round2(cost.amountTodayBlocking()));
    }

    /** 组装成本块：当日 tokens/金额 + 前 6 天与今日共 7 点趋势 + 阈值及其配置来源。*/
    private Map<String, Object> cost() {
        long tokens = cost.tokensTodayBlocking();
        long estimated = cost.tokensEstimatedBlocking();
        double today = round2(cost.amountTodayBlocking());
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate d = LocalDate.now();
        for (int i = 6; i >= 1; i--) {
            LocalDate day = d.minusDays(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("amount", round2(cost.amountBlocking(day)));
            trend.add(point);
        }
        trend.add(Map.of("date", d.toString(), "amount", today));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("today", today);
        c.put("threshold", costThreshold);
        c.put("thresholdSource", "app.monitor.cost-threshold");
        c.put("tokensToday", tokens);
        // 服务未回 usage 的那部分按字符数估算，单独报出来，避免把估算量当成计量结果
        c.put("tokensEstimated", estimated);
        c.put("amountIsEstimate", true);
        c.put("trend", trend);
        return c;
    }

    /** 近 30 分钟真实请求计数（MetricsWebFilter 按 epoch 分钟累加），没有流量就是 0 */
    private List<Map<String, Object>> requestTrend() {
        long nowMin = System.currentTimeMillis() / 60_000;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            long bucket = nowMin - i;
            AtomicLong actual = store.requestBuckets.get(bucket);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", LocalDateTime.ofInstant(new Date(bucket * 60_000).toInstant(),
                    TimeZone.getDefault().toZoneId()).format(HHMM));
            point.put("count", actual == null ? 0 : actual.get());
            out.add(point);
        }
        return out;
    }

    /** 逐条判定 4 个告警规则（P95/幻觉率/向量降级/费用阈值），无测得值时 message 如实写"未测得/未评测"。*/
    private List<Map<String, Object>> alerts(Double p95, double costToday) {
        Double hallucination = latestHallucinationRate();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(alert("warn", "P95 延迟 > 2000ms",
                p95 == null ? "本进程尚无延迟样本，P95 未测得（未测得不等于达标）"
                        : "当前 P95 为 " + round1(p95) + "ms（样本 " + store.latenciesSnapshot().size() + " 条）",
                p95 != null && p95 > 2000));
        out.add(alert("critical", "幻觉率 > 10%",
                hallucination == null
                        ? "尚无已完成的评测，幻觉率未知（未评测不等于合格）"
                        : String.format("最近评测幻觉率 %.1f%%（阈值 10%%）", hallucination),
                hallucination != null && hallucination > 10));
        out.add(alert("critical", "向量检索已降级",
                availability.pg
                        ? "向量检索走 pgvector"
                        : String.format("向量检索已回退内存实现，正在后台重探（待回放写操作 %d 条）",
                        chunkStore.pendingOps()),
                !availability.pg));
        // costThreshold 在这里被真正消费：只回显阈值不报警的话，它和监控页的一句说明没有区别
        out.add(alert("critical", "当日估算费用 ≥ 告警阈值",
                String.format("今日估算 %.2f 元 / 阈值 %.2f 元（阈值是配置项 app.monitor.cost-threshold，不是账单实数；"
                        + "问答熔断线为 app.chat.daily-cost-breaker-yuan）", costToday, costThreshold),
                costToday >= costThreshold));
        return out;
    }

    /** 拼单条告警记录 {level,rule,message,active}；active 表示该规则当前是否触发。*/
    private Map<String, Object> alert(String level, String rule, String message, boolean active) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("level", level);
        a.put("rule", rule);
        a.put("message", message);
        a.put("active", active);
        return a;
    }

    /** 最近一次完成评测的幻觉率；没有已完成的评测（或评测未判定）时返回 null，交由调用方如实显示"未知" */
    private Double latestHallucinationRate() {
        // 只读运行头部（D-30）：这里要的是 metrics 里的一个数，按实体读会把每次运行的样本明细整片拉进来
        for (DbStore.RunHead r : db.runHeadsDesc()) {
            if (!"done".equals(r.status)) continue;
            Double v = r.metrics.get("hallucinationRate");
            // 评测汇总用 -1 表示"没有任何样本被模型判定"，不是 0%
            if (v != null && v >= 0) return v;
        }
        return null;
    }

    /** 分位数：无样本返回 null（"未测得"）。返回 0 会被读成"很快"，这是 D-19 的原始形态。 */
    private Double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return (double) sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /** 延迟类数值取 1 位小数（p50Ms/p95Ms 及告警文案里的 P95 都用它）。*/
    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 金额类数值取 2 位小数（元；当日/趋势金额与告警阈值比较前都过它）。*/
    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
