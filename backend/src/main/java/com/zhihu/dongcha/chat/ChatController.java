package com.zhihu.dongcha.chat;

import com.zhihu.dongcha.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 问答 REST 入口：SSE 流式问答与会话列表/详情/删除，仅供前端调用。
 * 用户身份一律取自 AuthWebFilter 注入的 CurrentUser，不从请求体/查询参传。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 智能问答 SSE：事件 start / agent-update / token / citation / warning / done / error */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatService.ChatRequest req, ServerWebExchange ex) {
        return chatService.stream(CurrentUser.of(ex), req);
    }

    /** 当前用户的会话摘要列表；conversationsOf 走 JPA 阻塞读，故挂到 boundedElastic 线程 */
    @GetMapping("/conversations")
    public Mono<List<Map<String, Object>>> conversations(ServerWebExchange ex) {
        return Mono.fromCallable(() -> chatService.conversationsOf(CurrentUser.of(ex)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 取指定会话的消息（含引用卡片）；非本人会话由 ChatService.messagesOf 抛 403 */
    @GetMapping("/conversations/{id}/messages")
    public Mono<List<Map<String, Object>>> messages(@PathVariable String id, ServerWebExchange ex) {
        return Mono.fromCallable(() -> chatService.messagesOf(CurrentUser.of(ex), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 删除指定会话（先校验归属，非本人 403），成功返回 {deleted:true,id} */
    @DeleteMapping("/conversations/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id, ServerWebExchange ex) {
        return Mono.fromCallable(() -> {
                    chatService.deleteConversation(CurrentUser.of(ex), id);
                    return Map.<String, Object>of("deleted", true, "id", id);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
