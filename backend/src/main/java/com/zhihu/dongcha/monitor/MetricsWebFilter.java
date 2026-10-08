package com.zhihu.dongcha.monitor;

import com.zhihu.dongcha.store.Store;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 记录每个 /api 请求的延迟到环形缓冲（Order 最前，覆盖完整链路耗时） */
@Component
@Order(-200)
public class MetricsWebFilter implements WebFilter {

    private final Store store;

    public MetricsWebFilter(Store store) {
        this.store = store;
    }

    /** 仅对 /api 前缀计时（含 /api/health 探针与监控页轮询）：doFinally 在正常/错误/取消后都记录，失败请求也计入延迟。*/
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api")) return chain.filter(exchange);
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(sig -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            store.recordLatency(ms, System.currentTimeMillis() / 60_000);
        });
    }
}
