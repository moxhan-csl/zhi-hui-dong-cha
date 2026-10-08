package com.zhihu.dongcha.redis;

import com.zhihu.dongcha.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * LLM/embedding token 用量 → Redis 成本累计。
 * key: cost:tokens:{yyyyMMdd} (INCRBY 总 tokens)、cost:amount:{yyyyMMdd} (INCRBYFLOAT 估算金额)、
 * cost:tokens-est:{yyyyMMdd} (其中按字符数估算的部分)，TTL 8 天便于查看近 7 日趋势；
 * 金额按配置单价 price-per-1k-tokens 估算，不是账单实数。
 * 保留一份进程内当日兜底计数，供 Redis 不可用时监控读取。
 */
@Component
public class CostService {

    private static final Logger log = LoggerFactory.getLogger(CostService.class);
    private static final Duration TTL = Duration.ofDays(8);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisOps redis;
    private final double pricePer1k;

    /** 兜底：Redis 不可用时的当日累计 */
    private volatile long fallbackTokens;
    private volatile long fallbackEstimated;   // 其中按字符数估算的部分
    private volatile double fallbackAmount;    // 估算金额（¥，非账单实数）
    private volatile String fallbackDate = LocalDate.now().format(DAY); // 兜底计数所属日期，跨天即清零

    /** 注入 RedisOps 与单价 price-per-1k-tokens（金额按此单价估算，不是账单） */
    public CostService(RedisOps redis, @Value("${app.llm.price-per-1k-tokens}") double pricePer1k) {
        this.redis = redis;
        this.pricePer1k = pricePer1k;
    }

    /** 记录一次调用用量（fire-and-forget，监控读同一 key） */
    public Mono<Void> record(String model, LlmUsage usage) {
        if (usage == null || usage.total() <= 0) return Mono.empty();
        return Mono.fromRunnable(() -> recordBlocking(model, usage))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnNext(v -> log.debug("cost recorded model={} tokens={}", model, usage.total()))
                .then()
                .onErrorResume(e -> {
                    log.warn("cost record failed: {}", e.toString());
                    return Mono.empty();
                });
    }

    /** 阻塞版本：供解析/评测等已在工作线程上的调用方使用 */
    public void recordBlocking(String model, LlmUsage usage) {
        if (usage == null || usage.total() <= 0) return;
        String day = LocalDate.now().format(DAY);
        double amount = usage.total() / 1000.0 * pricePer1k;
        if (redis.available()) {
            redis.blockingIncr(Keys.tokens(day), usage.total(), TTL);
            redis.blockingIncrDouble(Keys.amount(day), amount, TTL);
            // 服务没回 usage 时按字符数估算，单独累计，否则"总 tokens"会把估算值冒充成真实计量
            if (usage.estimated()) redis.blockingIncr(Keys.estimated(day), usage.total(), TTL);
        } else {
            synchronized (this) {
                rolloverIfNeeded(day);
                fallbackTokens += usage.total();
                fallbackAmount += amount;
                if (usage.estimated()) fallbackEstimated += usage.total();
            }
        }
    }

    /** 当日累计 tokens（Redis 优先，兜底内存） */
    public Mono<Long> tokensToday() {
        return tokensOf(LocalDate.now());
    }

    /** 当日估算金额（¥） */
    public Mono<Double> amountToday() {
        return amountOf(LocalDate.now());
    }

    /** 指定日期金额，无数据返回 0 */
    public Mono<Double> amountOf(LocalDate day) {
        String d = day.format(DAY);
        boolean today = day.equals(LocalDate.now());
        if (!redis.available()) return Mono.just(today ? fallbackAmountValue(d) : 0d);
        return redis.getDouble(Keys.amount(d), 0)
                .map(v -> v > 0 || !today ? v : fallbackAmountValue(d))
                .onErrorResume(e -> Mono.just(0d));
    }

    /** 指定日期累计 tokens，无数据返回 0；Redis 不可用且查的是当天时取内存兜底 */
    public Mono<Long> tokensOf(LocalDate day) {
        String d = day.format(DAY);
        boolean today = day.equals(LocalDate.now());
        if (!redis.available()) return Mono.just(today ? fallbackTokensValue(d) : 0L);
        return redis.getLong(Keys.tokens(d), 0)
                .map(v -> v > 0 || !today ? v : fallbackTokensValue(d))
                .onErrorResume(e -> Mono.just(0L));
    }

    /** 监控页 blocking 场景便捷读取 */
    public long tokensTodayBlocking() {
        String d = LocalDate.now().format(DAY);
        if (!redis.available()) return fallbackTokensValue(d);
        long v = RedisOps.parseLong(redis.blockingGet(Keys.tokens(d)), -1);
        return v < 0 ? fallbackTokensValue(d) : v;
    }

    /** 当日累计中"按字符数估算"的 tokens（服务未回 usage 的部分），用于给总量标注可信度 */
    public long tokensEstimatedBlocking() {
        String d = LocalDate.now().format(DAY);
        if (!redis.available()) return fallbackEstimatedValue(d);
        long v = RedisOps.parseLong(redis.blockingGet(Keys.estimated(d)), -1);
        return v < 0 ? fallbackEstimatedValue(d) : v;
    }

    /** 监控页 blocking 场景便捷读取 */
    public double amountTodayBlocking() {
        String d = LocalDate.now().format(DAY);
        if (!redis.available()) return fallbackAmountValue(d);
        double v = RedisOps.parseDouble(redis.blockingGet(Keys.amount(d)), -1);
        return v < 0 ? fallbackAmountValue(d) : v;
    }

    /** 指定日期金额（blocking） */
    public double amountBlocking(LocalDate day) {
        String d = day.format(DAY);
        if (!redis.available()) return d.equals(LocalDate.now().format(DAY)) ? fallbackAmountValue(d) : 0d;
        return RedisOps.parseDouble(redis.blockingGet(Keys.amount(d)), 0);
    }

    /** 成本数据来源（监控页展示用）：redis 或内存兜底 */
    public String sourceName() {
        return redis.available() ? "redis" : "in-process-fallback";
    }

    /** 取当日兜底 tokens（读前先按 day 触发跨天清零） */
    private synchronized long fallbackTokensValue(String day) {
        rolloverIfNeeded(day);
        return fallbackTokens;
    }

    /** 取当日兜底金额（读前先按 day 触发跨天清零） */
    private synchronized double fallbackAmountValue(String day) {
        rolloverIfNeeded(day);
        return fallbackAmount;
    }

    /** 取当日兜底估算 tokens（读前先按 day 触发跨天清零） */
    private synchronized long fallbackEstimatedValue(String day) {
        rolloverIfNeeded(day);
        return fallbackEstimated;
    }

    /** 内存兜底计数的跨天翻篇：day 与记录的 fallbackDate 不同就重置三个累计值并记下新日期 */
    private void rolloverIfNeeded(String day) {
        if (!day.equals(fallbackDate)) {
            fallbackDate = day;
            fallbackTokens = 0;
            fallbackEstimated = 0;
            fallbackAmount = 0;
        }
    }

    /** 成本 Redis 键的集中构造，day 为 yyyyMMdd */
    public static final class Keys {
        private Keys() {
        }

        /** 当日累计总 tokens 键 */
        public static String tokens(String day) {
            return "cost:tokens:" + day;
        }

        /** 当日累计中"按字符估算"部分的键（与总键分存，供标注可信度） */
        public static String estimated(String day) {
            return "cost:tokens-est:" + day;
        }

        /** 当日估算金额键（INCRBYFLOAT 存浮点） */
        public static String amount(String day) {
            return "cost:amount:" + day;
        }
    }
}
