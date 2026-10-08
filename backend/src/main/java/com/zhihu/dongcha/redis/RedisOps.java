package com.zhihu.dongcha.redis;

import com.zhihu.dongcha.config.Availability;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 读写门面（Lettuce StringRedisTemplate）。
 * 启动探针失败或运行期异常时 availability.redis=false，全部计数/缓存走进程内 Map 兜底：
 * 功能不中断，只失去跨实例共享与重启存活。
 * <p>阻塞方法供已处在 boundedElastic / 工作线程的调用方直接使用；
 * 非阻塞调用方使用 Mono 版本（内部统一 subscribeOn(boundedElastic)）。
 */
@Component
public class RedisOps {

    private static final Logger log = LoggerFactory.getLogger(RedisOps.class);

    private final StringRedisTemplate redis;
    private final Availability availability;

    /** Redis 不可用时的回退存储：key -> value + 过期时间 */
    private final Map<String, Entry> fallback = new ConcurrentHashMap<>();

    /** 内存兜底条目：字符串值 + 过期时刻（expireAt 绝对毫秒） */
    private static class Entry {
        String value;
        long expireAt;   // 0 = 永不过期
    }

    /** 注入 Lettuce 的 StringRedisTemplate 与可用性开关 Availability.redis（决定走真连还是内存 Map） */
    public RedisOps(StringRedisTemplate redis, Availability availability) {
        this.redis = redis;
        this.availability = availability;
    }

    /** 启动时 PING 探活：结果写回 availability.redis，连不上即置 false 让后续读写全部走内存兜底 */
    @PostConstruct
    public void probe() {
        try {
            String pong = redis.execute((RedisCallback<String>) RedisConnection::ping);
            availability.redis = "PONG".equalsIgnoreCase(pong);
            log.info("[health] Redis {}（AUTH/PING={}）", availability.redis ? "连接正常" : "PING 异常返回", pong);
        } catch (Exception e) {
            availability.redis = false;
            log.warn("[health] Redis 不可用，登录冷却/回答缓存/成本统计降级为内存实现: {}", e.toString());
        }
    }

    /** 当前是否可用：由启动探针与各次操作的降级共同维护的单一布尔位 */
    public boolean available() {
        return availability.redis;
    }

    // ==================== 阻塞 API ====================

    /** 读字符串键；Redis 不可用或读异常时回退读内存 Map（异常不外抛） */
    public String blockingGet(String key) {
        if (!available()) return fallbackGet(key);
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            degrade("get", e);
            return fallbackGet(key);
        }
    }

    /** 整数累加（INCRBY），ttl 非空则每次续期；不可用或异常时回退内存计数 */
    public long blockingIncr(String key, long delta, Duration ttl) {
        if (!available()) return fallbackIncr(key, delta, ttl);
        try {
            Long v = redis.opsForValue().increment(key, delta);
            if (ttl != null) redis.expire(key, ttl);
            return v == null ? 0L : v;
        } catch (RuntimeException e) {
            degrade("incr", e);
            return fallbackIncr(key, delta, ttl);
        }
    }

    /** 金额等浮点累计：Redis 内部按 1e-6 精度存储（INCRBYFLOAT） */
    public double blockingIncrDouble(String key, double delta, Duration ttl) {
        if (!available()) return fallbackIncrScaled(key, delta, ttl);
        try {
            Double v = redis.opsForValue().increment(key, delta);
            if (ttl != null) redis.expire(key, ttl);
            return v == null ? 0d : v;
        } catch (RuntimeException e) {
            degrade("incrDouble", e);
            return fallbackIncrScaled(key, delta, ttl);
        }
    }

    /** 写字符串键并设 TTL；Redis 不可用或异常时改写内存 Map */
    public void blockingSet(String key, String value, Duration ttl) {
        if (!available()) {
            putFallback(key, value, ttl);
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            degrade("set", e);
            putFallback(key, value, ttl);
        }
    }

    /** 删键；Redis 不可用或异常时同步删掉内存 Map 里的同名条目 */
    public void blockingDel(String key) {
        if (!available()) {
            fallback.remove(key);
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            degrade("del", e);
            fallback.remove(key);
        }
    }

    /** 剩余秒数：-2 不存在，-1 无过期时间 */
    public long blockingTtl(String key) {
        if (!available()) {
            Entry e = liveFallback(key);
            if (e == null) return -2;
            return e.expireAt == 0 ? -1 : Math.max(0, (e.expireAt - System.currentTimeMillis()) / 1000);
        }
        try {
            Long t = redis.getExpire(key);
            return t == null ? -2 : t;
        } catch (RuntimeException e) {
            degrade("ttl", e);
            Entry en = liveFallback(key);
            return en == null ? -2 : (en.expireAt == 0 ? -1 : Math.max(0, (en.expireAt - System.currentTimeMillis()) / 1000));
        }
    }

    // ==================== 响应式包装 ====================

    /** get 的响应式包装：内部仍走 blockingGet，统一 subscribeOn(boundedElastic) */
    public Mono<String> get(String key) {
        return Mono.fromCallable(() -> blockingGet(key)).subscribeOn(Schedulers.boundedElastic());
    }

    /** incr 的响应式包装（boundedElastic） */
    public Mono<Long> incr(String key, long delta, Duration ttl) {
        return Mono.fromCallable(() -> blockingIncr(key, delta, ttl)).subscribeOn(Schedulers.boundedElastic());
    }

    /** incrDouble 的响应式包装（boundedElastic） */
    public Mono<Double> incrDouble(String key, double delta, Duration ttl) {
        return Mono.fromCallable(() -> blockingIncrDouble(key, delta, ttl)).subscribeOn(Schedulers.boundedElastic());
    }

    /** set 的响应式包装：结果以内部兜底为准，恒 emit true */
    public Mono<Boolean> set(String key, String value, Duration ttl) {
        return Mono.fromCallable(() -> {
            blockingSet(key, value, ttl);
            return true;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** del 的响应式包装：恒 emit true */
    public Mono<Boolean> del(String key) {
        return Mono.fromCallable(() -> {
            blockingDel(key);
            return true;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** ttl 的响应式包装（返回值口径同 blockingTtl） */
    public Mono<Long> ttl(String key) {
        return Mono.fromCallable(() -> blockingTtl(key)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 读键并解析为 long；键缺失或解析失败取默认值 dflt */
    public Mono<Long> getLong(String key, long dflt) {
        return get(key).map(v -> parseLong(v, dflt)).defaultIfEmpty(dflt);
    }

    /** 读键并解析为 double；键缺失或解析失败取默认值 dflt */
    public Mono<Double> getDouble(String key, double dflt) {
        return get(key).map(v -> parseDouble(v, dflt)).defaultIfEmpty(dflt);
    }

    // ==================== helpers ====================

    /** 宽松解析为 long：先按 double 转再截断，以兼容 Redis 存的 "1.0" 之类写法；null 或异常取 dflt */
    public static long parseLong(String v, long dflt) {
        try {
            return v == null ? dflt : (long) Double.parseDouble(v);
        } catch (Exception e) {
            return dflt;
        }
    }

    /** 解析为 double；null 或异常取 dflt */
    public static double parseDouble(String v, double dflt) {
        try {
            return v == null ? dflt : Double.parseDouble(v);
        } catch (Exception e) {
            return dflt;
        }
    }

    /** 记一次运行期降级：仅在尚标记可用时打 warn，随后把 availability.redis 置 false（此后不再重复告警） */
    private void degrade(String op, RuntimeException e) {
        if (availability.redis) log.warn("[redis] {} 失败，降级为内存实现: {}", op, e.toString());
        availability.redis = false;
    }

    /** Redis 不可用时的内存版 INCRBY：命中未过期条目则保留原 TTL 窗口，否则按新 ttl 起算 */
    private synchronized long fallbackIncr(String key, long delta, Duration ttl) {
        Entry en = liveFallback(key);
        long base = en == null ? 0 : parseLong(en.value, 0);
        long v = base + delta;
        Entry next = new Entry();
        next.value = String.valueOf(v);
        // 已有未过期 TTL 时保留原窗口，否则按新 TTL 起算
        next.expireAt = en != null && en.expireAt > System.currentTimeMillis()
                ? en.expireAt : (ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis());
        fallback.put(key, next);
        return v;
    }

    /** 内存版浮点累加：结果四舍五入到 1e-6，近似 Redis INCRBYFLOAT 的存储精度 */
    private synchronized double fallbackIncrScaled(String key, double delta, Duration ttl) {
        Entry en = liveFallback(key);
        double base = en == null ? 0 : parseDouble(en.value, 0);
        double v = Math.round((base + delta) * 1_000_000d) / 1_000_000d;
        Entry next = new Entry();
        next.value = String.valueOf(v);
        next.expireAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        fallback.put(key, next);
        return v;
    }

    /** 读内存兜底值：经 liveFallback 过滤，已过期视为不存在返回 null */
    private String fallbackGet(String key) {
        Entry e = liveFallback(key);
        return e == null ? null : e.value;
    }

    /** 写内存兜底值：ttl 为 null 记为永不过期（expireAt=0） */
    private synchronized void putFallback(String key, String value, Duration ttl) {
        Entry e = new Entry();
        e.value = value;
        e.expireAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        fallback.put(key, e);
    }

    /** 取内存条目并顺带清过期：不存在或已过期都返回 null（过期时从 Map 删除） */
    private Entry liveFallback(String key) {
        Entry e = fallback.get(key);
        if (e == null) return null;
        if (e.expireAt != 0 && e.expireAt < System.currentTimeMillis()) {
            fallback.remove(key);
            return null;
        }
        return e;
    }
}
