package com.zhihu.dongcha.llm;

/**
 * 流式块：text 为增量文本（可为空），usage 非空表示这是携带 token 用量的尾块。
 */
public record LlmChunk(String text, LlmUsage usage) {

    /** ChatService 逐块拼句并下发 token 事件，EvalService 也只拼 text，两处都不读 usage */
    public static LlmChunk text(String t) {
        return new LlmChunk(t, null);
    }

    /** 用量尾块：目前只有 mock 流会发，下游因 text=null 跳过它；真实实现改为直接上报 CostService */
    public static LlmChunk usage(LlmUsage u) {
        return new LlmChunk(null, u);
    }
}
