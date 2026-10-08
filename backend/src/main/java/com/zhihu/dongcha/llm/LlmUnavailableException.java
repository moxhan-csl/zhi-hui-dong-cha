package com.zhihu.dongcha.llm;

/** 真实 LLM/embedding 服务不可用（网络、鉴权、限流、返回异常）时抛出，供上层降级或标记失败 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
