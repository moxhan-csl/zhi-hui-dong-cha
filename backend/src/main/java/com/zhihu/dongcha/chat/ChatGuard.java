package com.zhihu.dongcha.chat;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.redis.CostService;
import com.zhihu.dongcha.redis.RedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问答入口护栏（D-31 配额 + D-32 成本拦截）。三道闸，按"最便宜→最贵"顺序判：
 * <ol>
 *   <li>日成本熔断：当日估算金额达到 {@code app.chat.daily-cost-breaker-yuan} 即全站拒绝；</li>
 *   <li>单用户日请求数：Redis 计数 {@code chat:quota:{yyyyMMdd}:{userId}}，TTL 到次日零点后 1 小时；</li>
 *   <li>在途并发：进程内计数，单用户 {@code max-inflight-per-user}、全局 {@code max-inflight-total}。</li>
 * </ol>
 * 在途名额必须在流终止时归还，故 {@link #acquire} 返回 release 回调，由调用方 {@code doFinally} 触发；
 * 双保险见 {@link #releaseWith} 的幂等包装。
 * <p>Redis 不可用时日配额自动退化为进程内计数（RedisOps 兜底），单实例部署行为不变。
 */
@Component
public class ChatGuard {

    private static final Logger log = LoggerFactory.getLogger(ChatGuard.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisOps redis;
    private final CostService cost;
    private final int maxInflightPerUser;
    private final int maxInflightTotal;
    private final long dailyRequestLimit;
    private final double costBreakerYuan;

    private final Map<String, AtomicInteger> inflightPerUser = new ConcurrentHashMap<>();
    private final AtomicInteger inflightTotal = new AtomicInteger();

    public ChatGuard(RedisOps redis, CostService cost,
                     @Value("${app.chat.max-inflight-per-user}") int maxInflightPerUser,
                     @Value("${app.chat.max-inflight-total}") int maxInflightTotal,
                     @Value("${app.chat.daily-request-limit}") long dailyRequestLimit,
                     @Value("${app.chat.daily-cost-breaker-yuan}") double costBreakerYuan) {
        this.redis = redis;
        this.cost = cost;
        this.maxInflightPerUser = maxInflightPerUser;
        this.maxInflightTotal = maxInflightTotal;
        this.dailyRequestLimit = dailyRequestLimit;
        this.costBreakerYuan = costBreakerYuan;
    }

    /**
     * 检查并占用一个在途名额。被拒时 Mono 以 {@link BizException} 失败，
     * 通过时发出 release 回调（幂等，可安全多次调用）。
     * 内含阻塞 Redis 读，统一在 boundedElastic 上执行。
     */
    public Mono<Runnable> acquire(String userId) {
        return Mono.fromCallable(() -> acquireBlocking(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 三道闸的实际判定体（阻塞，跑在 boundedElastic 上）：日成本熔断→单用户日配额→在途并发；通过则返回幂等归还回调 */
    private Runnable acquireBlocking(String userId) {
        if (costBreakerYuan > 0) {
            double used = cost.amountTodayBlocking();
            if (used >= costBreakerYuan) {
                throw BizException.tooManyRequests("COST_BREAKER",
                        "今日模型费用已达熔断线（估算 " + money(used) + " / 上限 " + money(costBreakerYuan)
                                + " 元），问答暂停以控制成本。",
                        (int) secondsToTomorrow());
            }
        }

        if (dailyRequestLimit > 0) {
            String day = LocalDate.now().format(DAY);
            long used = redis.blockingIncr("chat:quota:" + day + ":" + userId, 1,
                    Duration.ofSeconds(secondsToTomorrow() + 3600));
            if (used > dailyRequestLimit) {
                throw BizException.tooManyRequests("DAILY_QUOTA",
                        "今日提问次数已达上限（" + dailyRequestLimit + " 次），明日零点自动恢复。",
                        (int) secondsToTomorrow());
            }
        }

        AtomicInteger mine = inflightPerUser.computeIfAbsent(userId, k -> new AtomicInteger());
        int now = mine.incrementAndGet();
        int total = inflightTotal.incrementAndGet();
        if (now > maxInflightPerUser || total > maxInflightTotal) {
            mine.decrementAndGet();
            inflightTotal.decrementAndGet();
            String msg = now > maxInflightPerUser
                    ? "你有 " + maxInflightPerUser + " 个提问正在生成中，请等待其中一个完成后再问。"
                    : "同时生成的提问已达上限（" + maxInflightTotal + " 个），请稍后再试。";
            throw BizException.tooManyRequests("TOO_MANY_INFLIGHT", msg, 5);
        }

        AtomicBoolean released = new AtomicBoolean(false);
        return () -> {
            if (released.compareAndSet(false, true)) {
                mine.decrementAndGet();
                inflightTotal.decrementAndGet();
            }
        };
    }

    /** 兜底归还：流以异常/取消等方式终止而 release 未被调用时，交由 doFinally 统一触发 */
    public void releaseWith(Runnable release) {
        try {
            if (release != null) release.run();
        } catch (Exception e) {
            log.warn("chat guard release failed: {}", e.toString());
        }
    }

    /** 被拒时给前端的建议等待秒数 */
    public static long secondsToTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds();
    }

    /** 金额保留两位小数，仅用于熔断错误文案里的数字展示 */
    private static String money(double v) {
        return String.format("%.2f", v);
    }
}
