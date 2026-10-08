package com.zhihu.dongcha.config;

import com.zhihu.dongcha.rag.DelegatingChunkStore;
import com.zhihu.dongcha.rag.MemoryChunkStore;
import com.zhihu.dongcha.rag.PgChunkStore;
import com.zhihu.dongcha.store.Store;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL/pgvector 第二数据源：手工构建（Spring Boot 自动装配的数据源已给 MySQL JPA 主库），
 * 仅暴露一个 JdbcTemplate 与向量库实现选择。连不上则 WARN 并降级为内存向量检索。
 */
@Configuration
public class PgConfig {

    private static final Logger log = LoggerFactory.getLogger(PgConfig.class);

    /** 手建 Hikari 连 pgvector 装出 DelegatingChunkStore：建表成功用 Pg，失败置 availability.pg=false 转内存并留实例待后台重探回放。*/
    @Bean
    public DelegatingChunkStore chunkStore(Store store, Availability availability,
                                 @Value("${app.pg.enabled}") boolean pgEnabled,
                                 @Value("${app.pg.url}") String url,
                                 @Value("${app.pg.username}") String username,
                                 @Value("${app.pg.password}") String password,
                                 @Value("${app.pg.maximum-pool-size:6}") int maxPool,
                                 @Value("${app.pg.minimum-idle:2}") int minIdle,
                                 @Value("${app.pg.connect-timeout-ms:8000}") long connectTimeoutMs,
                                 @Value("${app.pg.keepalive-ms:30000}") long keepaliveMs,
                                 @Value("${app.pg.max-lifetime-ms:240000}") long maxLifetimeMs,
                                 @Value("${app.pg.idle-timeout-ms:180000}") long idleTimeoutMs,
                                 @Value("${app.pg.reprobe-interval-ms:30000}") long reprobeIntervalMs,
                                 @Value("${app.llm.embedding-dim}") int dim) {
        MemoryChunkStore memory = new MemoryChunkStore(store);
        if (!pgEnabled) {
            availability.pg = false;
            log.warn("[health] app.pg.enabled=false，向量检索使用内存实现");
            return new DelegatingChunkStore(null, memory, availability);
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("pg-vector-pool");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(maxPool);
        ds.setMinimumIdle(Math.min(minIdle, maxPool));
        ds.setConnectionTimeout(connectTimeoutMs);
        // 远程库会掐掉空闲连接：若只在借出时校验，失效连接会直接抛给请求线程并触发降级，
        // 因此主动保活 + 把连接寿命收敛到远端空闲阈值之内。
        ds.setKeepaliveTime(keepaliveMs);
        ds.setMaxLifetime(maxLifetimeMs);
        ds.setIdleTimeout(idleTimeoutMs);
        ds.setValidationTimeout(3000);
        ds.setInitializationFailTimeout(-1);   // 构造期不连库，探针由 initSchema 负责
        PgChunkStore pg = new PgChunkStore(new JdbcTemplate(ds), dim);
        try {
            pg.initSchema();
            availability.pg = true;
            log.info("[health] PostgreSQL/pgvector 就绪: {} (dim={}, chunks={})", url, dim, pg.countAll());
        } catch (Exception e) {
            // 仍然持有 pg 实例：降级只是暂时的，PgStoreRecovery 会周期性重探并回放排队的写操作
            availability.pg = false;
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.warn("[health] PostgreSQL 不可用，向量检索暂用内存实现，后台将每 {}ms 重探: {} / root: {}",
                    reprobeIntervalMs, e, root.getMessage(), e);
        }
        return new DelegatingChunkStore(pg, memory, availability);
    }
}
