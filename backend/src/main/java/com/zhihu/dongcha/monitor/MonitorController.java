package com.zhihu.dongcha.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/** 监控中心（SYS_ADMIN，角色由 AuthWebFilter 拦截）；Redis/JPA 读在 boundedElastic 上执行 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** GET /api/monitor/metrics：聚合含 Redis/JPA 阻塞读，切到 boundedElastic 以免堵 WebFlux 事件循环。*/
    @GetMapping("/metrics")
    public Mono<Map<String, Object>> metrics() {
        return Mono.fromCallable(monitorService::metrics).subscribeOn(Schedulers.boundedElastic());
    }

    /** GET /api/monitor/alerts：单独取告警列表，同样把阻塞读放到 boundedElastic 上执行。*/
    @GetMapping("/alerts")
    public Mono<List<Map<String, Object>>> alerts() {
        return Mono.fromCallable(monitorService::alerts).subscribeOn(Schedulers.boundedElastic());
    }
}
