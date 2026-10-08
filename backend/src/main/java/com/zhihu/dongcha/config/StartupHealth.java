package com.zhihu.dongcha.config;

import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.rag.ChunkStore;
import com.zhihu.dongcha.redis.RedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动健康报告：把三个外部依赖（LLM / pgvector / Redis）的可用性一次性打印清楚。
 * 任一不可用时对应功能已在装配阶段自动降级（llm→mock、向量→内存、redis→内存）；
 * MySQL 不可用会让 JPA 装配直接失败，进程退出（业务数据以 MySQL 为准，不做静默降级）。
 */
@Component
public class StartupHealth {

    private static final Logger log = LoggerFactory.getLogger(StartupHealth.class);

    private final Availability availability;
    private final ChunkStore chunkStore;
    private final RedisOps redisOps;
    private final DbStore dbStore;

    public StartupHealth(Availability availability, ChunkStore chunkStore, RedisOps redisOps, DbStore dbStore) {
        this.availability = availability;
        this.chunkStore = chunkStore;
        this.redisOps = redisOps;
        this.dbStore = dbStore;
    }

    /** 应用就绪后一次性打印三条外部依赖的状态；每个降级开关再补一条 WARN 说明用户可感知的后果。*/
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("[health] 外部依赖: {} | mysql=ok (users={} 镜像, docs={} 行) | vector-store={} | redis={}",
                availability.summary(),
                dbStore.cache().users.size(), dbStore.countDocs(),
                chunkStore.mode(), redisOps.available() ? "online" : "offline(in-memory)");
        if (!availability.llm) log.warn("[health] LLM 处于降级模式：问答使用本地模板合成，回答不含真实模型生成内容");
        if (!availability.pg) log.warn("[health] 向量检索处于降级模式：使用进程内余弦检索，重启后向量需重新构建");
        if (!availability.redis) log.warn("[health] Redis 处于降级模式：登录冷却/回答缓存/成本仅在本进程内有效");
    }
}
