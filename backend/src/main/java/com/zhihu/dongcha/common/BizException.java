package com.zhihu.dongcha.common;

import org.springframework.http.HttpStatus;

/** 业务异常：由 ApiExceptionHandler 转成统一错误体 {code,message}. */
public class BizException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public final Integer retryAfter;

    /** 不带 retryAfter 的构造：委托全参构造并置 null，错误体因此省略该字段。*/
    public BizException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** 全参构造：status/code/retryAfter 存为 public final，供 ApiExceptionHandler 直接读取拼响应。*/
    public BizException(HttpStatus status, String code, String message, Integer retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    /** 404 + code=NOT_FOUND。*/
    public static BizException notFound(String msg) {
        return new BizException(HttpStatus.NOT_FOUND, "NOT_FOUND", msg);
    }

    /** 400 + code=INVALID_PARAM。*/
    public static BizException badRequest(String msg) {
        return new BizException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", msg);
    }

    /** 403 + code=FORBIDDEN。*/
    public static BizException forbidden(String msg) {
        return new BizException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg);
    }

    /** 429：retryAfter 为建议重试秒数，随错误体一起下发（前端据此提示"多久后再试"） */
    public static BizException tooManyRequests(String code, String msg, Integer retryAfter) {
        return new BizException(HttpStatus.TOO_MANY_REQUESTS, code, msg, retryAfter);
    }
}
