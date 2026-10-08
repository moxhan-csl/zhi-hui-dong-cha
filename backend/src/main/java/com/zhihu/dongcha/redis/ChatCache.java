package com.zhihu.dongcha.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhihu.dongcha.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * 回答缓存：key = chat:sha1(gen + question + kbScope + histKey)，TTL 默认 10 分钟。
 * kbScope 取"本次提问实际可见并参与检索的知识库 id 有序集合"，避免跨可见性串答案。
 * histKey 取"本次进 prompt 的那段会话历史的消息 id 窗口"（D-11）：历史现在会进模型，
 * 同一个问题在不同上下文下必须落到不同的 key，否则追问会拿首轮答案当回答。
 * 用 id 而不是内容做指纹：消息落库后内容不再变动，id 窗口相同即上下文相同；无历史时为空串。
 * gen 是全局失效代号（Redis 计数器 chat:gen）：分块内容或可见范围一变就 INCR，
 * 于是所有旧 key 立刻读不到（无需 SCAN/DEL，也拦住了"生成途中被改库、回填时仍写旧代号"的迟到写入）。
 * Redis 不可用时 RedisOps 自动内存兜底（进程内命中，重启失效；代号也随之归零，只是失去跨实例失效能力）。
 */
@Service
public class ChatCache {

    private static final Logger log = LoggerFactory.getLogger(ChatCache.class);

    /** 全局失效代号；不给 TTL，复位会让旧 key 重新可读 */
    private static final String GEN_KEY = "chat:gen";

    private final RedisOps redis;
    private final long ttlSeconds;
    private final boolean enabled;

    /** 由配置注入 TTL 与开关；enabled=false 时 probe/put 直接空转，不读不写 Redis */
    public ChatCache(RedisOps redis,
                     @Value("${app.redis.chat-cache-ttl-seconds}") long ttlSeconds,
                     @Value("${app.redis.chat-cache-enabled}") boolean enabled) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
        this.enabled = enabled;
    }

    /** 缓存值：一条完整回答（会话id/答案/引用/质量结论/模型名/写入毫秒时间戳） */
    public record Cached(String conversationId, String answer, List<Map<String, Object>> citations,
                         boolean qualityPassed, String model, long cachedAt) {
    }

    /** 一次查缓存的结果：代号要回传给回填方，迟到的回填才会被自然丢弃 */
    public record Probe(long gen, Optional<Cached> cached) {
        public boolean hit() {
            return cached.isPresent();
        }
    }

    /** 拼缓存键：sha1(gen|问题去空格|排序后可见库id,逗号连接|histKey)，前缀 chat:；范围排序保证同集合同键 */
    static String keyOf(String question, Collection<String> kbScope, String histKey, long gen) {
        List<String> scope = new ArrayList<>(kbScope);
        Collections.sort(scope);
        String raw = gen + "|" + question.trim() + "|" + String.join(",", scope)
                + "|" + (histKey == null ? "" : histKey);
        return "chat:" + sha1(raw);
    }

    /** 查一次缓存：先取全局代号 gen 再按 key 读；未启用、未命中、读或反序列化失败一律回 miss（gen 仍回传给回填方） */
    public Mono<Probe> probe(String question, Collection<String> kbScope, String histKey) {
        if (!enabled) return Mono.just(new Probe(0L, Optional.empty()));
        return generation().flatMap(gen -> redis.get(keyOf(question, kbScope, histKey, gen))
                .map(v -> {
                    if (v == null || v.isBlank()) return new Probe(gen, Optional.<Cached>empty());
                    try {
                        return new Probe(gen, Optional.ofNullable(Json.MAPPER.readValue(v, Cached.class)));
                    } catch (Exception e) {
                        log.warn("chat cache deserialize failed: {}", e.toString());
                        return new Probe(gen, Optional.<Cached>empty());
                    }
                })
                .defaultIfEmpty(new Probe(gen, Optional.empty()))
                .onErrorResume(e -> Mono.just(new Probe(gen, Optional.empty()))));
    }

    /** 回填缓存：用查缓存时拿到的同一个 gen 组键后写入并设 TTL（代号若已前移则写成读不到的旧键）；未启用或序列化失败即静默跳过 */
    public Mono<Void> put(String question, Collection<String> kbScope, String histKey, long gen, Cached entry) {
        if (!enabled) return Mono.empty();
        final String v;
        try {
            v = Json.MAPPER.writeValueAsString(entry);
        } catch (Exception e) {
            log.warn("chat cache serialize failed: {}", e.toString());
            return Mono.empty();
        }
        return redis.set(keyOf(question, kbScope, histKey, gen), v, Duration.ofSeconds(ttlSeconds)).then();
    }

    /**
     * 使全部已缓存回答失效。给已在 boundedElastic 上的调用方（摄取/删库/改可见范围）直接阻塞调用。
     * 旧 key 不删：它们只是永远读不到，靠各自 TTL 消失。
     */
    public void invalidate() {
        long gen = redis.blockingIncr(GEN_KEY, 1, null);
        log.info("[cache] 回答缓存已整体失效，新代号 gen={}", gen);
    }

    /** 读全局失效代号（chat:gen），读失败时按 0 兜底 */
    private Mono<Long> generation() {
        return redis.getLong(GEN_KEY, 0L).onErrorReturn(0L);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 对键原文取 SHA-1 十六进制；JVM 无该算法时退化为 hashCode 的十六进制（仍稳定，只是分布差些） */
    private static String sha1(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /** 缓存值反序列化用（citations 为通用 Map） */
    public static final TypeReference<Cached> CACHED_TYPE = new TypeReference<>() {
    };
}
