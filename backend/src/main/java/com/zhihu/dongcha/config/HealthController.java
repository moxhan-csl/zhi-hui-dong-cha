package com.zhihu.dongcha.config;

import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.rag.DelegatingChunkStore;
import com.zhihu.dongcha.redis.RedisOps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 免鉴权健康探针，供容器 healthcheck、nginx 与运维使用。
 * 只读进程内的可用性开关和内存镜像，不放行任何外部调用：
 * 探针若真的去连 pg/mysql，失败时会顺带触发降级路由，反而把观测变成副作用。
 * 进程能应答即返回 200；降级状态在 body 里如实标出，不参与存活判定
 * （向量库/Redis 降级会由后台重探自愈，靠重启容器既解决不了也丢队列）。
 * <p>D-30：documents 内存镜像删掉后，这里的 {@code documents} 是定时任务（与 users 同一个 TTL）
 * 刷进 {@link DbStore#docCount()} 的读数，<b>最多滞后一个 TTL 周期</b>，是仪表不是精确计数。
 * 没有把它改成探针现查 COUNT：那等于让 MySQL 抖动通过 healthcheck 变成容器重启。
 */
@RestController
public class HealthController {

    private final Availability availability;
    private final DelegatingChunkStore chunkStore;
    private final RedisOps redisOps;
    private final DbStore db;

    public HealthController(Availability availability, DelegatingChunkStore chunkStore,
                            RedisOps redisOps, DbStore db) {
        this.availability = availability;
        this.chunkStore = chunkStore;
        this.redisOps = redisOps;
        this.db = db;
    }

    /** GET /api/health：读进程内开关与内存镜像拼状态图；全开为 UP、任一 off 标 DEGRADED，HTTP 恒返回 200。*/
    @GetMapping("/api/health")
    public Mono<Map<String, Object>> health() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", ready() ? "UP" : "DEGRADED");
            out.put("llm", availability.llm ? "openai-compatible" : "mock");
            out.put("vectorStore", chunkStore.mode());
            out.put("pendingVectorOps", chunkStore.pendingOps());
            out.put("redis", redisOps.available() ? "redis" : "in-memory");
            out.put("users", db.cache().users.size());
            out.put("documents", db.docCount());
            return out;
        });
    }

    /** 三开关全 true 才算就绪；只决定 body 里的 status 字段，不改变 HTTP 状态码。*/
    private boolean ready() {
        return availability.llm && availability.pg && availability.redis;
    }
}
