package com.zhihu.dongcha.llm;

/**
 * 没有真实 embedding 服务、且未显式允许哈希假向量时抛出（D-14）。
 * 与 {@link LlmUnavailableException} 的区别：那是"这次调用失败、可以再试"，
 * 这是"配置层就没有可用向量服务，重试多少次都一样"，所以上层不该重试。
 */
public class HashVectorRejectedException extends RuntimeException {

    public HashVectorRejectedException(String message) {
        super(message);
    }
}
