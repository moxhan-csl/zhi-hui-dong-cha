package com.zhihu.dongcha.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 工具：全局共享一个 NON_NULL 的 ObjectMapper，序列化时省略值为 null 的字段。
 * 供各处需要把对象写成 JSON 字符串时使用；只暴露 write，不做反序列化。
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {
    }

    /** 序列化为 JSON 字符串；失败包装成 RuntimeException 抛出，调用方无需处理 Jackson 受检异常。*/
    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }
}
