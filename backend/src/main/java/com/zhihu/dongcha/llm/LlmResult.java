package com.zhihu.dongcha.llm;

// usage 已在 provider 内部上报给 CostService，现有调用方（JudgeService 两处）只读 text
/** 非流式补全结果 */
public record LlmResult(String text, LlmUsage usage) {
}
