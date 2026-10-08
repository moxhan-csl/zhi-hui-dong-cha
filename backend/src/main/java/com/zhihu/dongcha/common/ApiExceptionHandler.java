package com.zhihu.dongcha.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常出口（@RestControllerAdvice）：把异常统一转成 {code,message[,retryAfter]} 的 JSON 错误体。
 * 运行于 WebFlux 栈——各 handler 返回 Mono<ResponseEntity>，而非 MVC 的同步返回。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** BizException 原样映射：HTTP 状态取 e.status，body 带 code/message，仅在 retryAfter 非空时附带。*/
    @ExceptionHandler(BizException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handle(BizException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        if (e.retryAfter != null) body.put("retryAfter", e.retryAfter);
        return Mono.just(ResponseEntity.status(e.status).body(body));
    }

    /**
     * WebFlux 里"路径没映射上"是 ResourceWebHandler 抛的 ResponseStatusException(404)，
     * 原先一律落到下面的 Exception 兜底 → 客户端收到 500，还把框架内部文案
     * （{@code 404 NOT_FOUND "No static resource api/xxx."}）原样透出去。
     * 只分 404 这一支：其余状态维持改动前的响应，免得顺手改掉别的可观测行为。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleStatus(ResponseStatusException e) {
        if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", "接口不存在")));
        }
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", e.getMessage() == null ? "服务器内部错误" : e.getMessage())));
    }

    /** 兜底未预期异常：统一转 500 + INTERNAL_ERROR，message 取异常文案，为 null 时用"服务器内部错误"。*/
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleOther(Exception e) {
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", e.getMessage() == null ? "服务器内部错误" : e.getMessage())));
    }

    /** 拼两字段错误体；用 LinkedHashMap 固定 code 排在 message 之前的顺序。*/
    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
