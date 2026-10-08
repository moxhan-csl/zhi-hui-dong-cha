package com.zhihu.dongcha.llm;

/** 一次调用的 token 用量（服务未返回 usage 时为估算值，estimated=true） */
public record LlmUsage(long promptTokens, long completionTokens, boolean estimated) {

    /** CostService 的记账基数：total<=0 直接跳过，按千字折算的费用也由它算 */
    public long total() {
        return promptTokens + completionTokens;
    }
}
