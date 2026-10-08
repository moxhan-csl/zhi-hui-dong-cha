package com.zhihu.dongcha.config;

import org.springframework.stereotype.Component;

/**
 * 外部服务可用性开关：启动健康检查写入，运行期调用方据此路由真实实现或内存回退。
 */
@Component
public class Availability {

    /** 真实 LLM（OpenAI 兼容接口）可用；false 时问答/embedding 走内存回退实现 */
    public volatile boolean llm = true;
    /** PostgreSQL + pgvector 可用；false 时向量检索走内存回退 */
    public volatile boolean pg = true;
    /** Redis 可用；false 时登录冷却/回答缓存/成本走内存回退 */
    public volatile boolean redis = true;

    /** 一行文本概述三个开关各走真实实现还是内存回退，供 StartupHealth 启动报告打印。*/
    public String summary() {
        return String.format("llm=%s, vectors=%s, redis=%s",
                llm ? "openai-compatible" : "mock",
                pg ? "pgvector" : "in-memory",
                redis ? "redis" : "in-memory");
    }
}
