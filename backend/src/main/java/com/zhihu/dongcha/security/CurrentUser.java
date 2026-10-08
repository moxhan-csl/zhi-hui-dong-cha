package com.zhihu.dongcha.security;

import com.zhihu.dongcha.model.User;
import org.springframework.web.server.ServerWebExchange;

/**
 * 当前登录用户的取用入口：AuthWebFilter 验签通过后把 User 写进 exchange 属性，Controller 用 {@link #of} 读回。
 * 纯静态工具类，不可实例化。
 */
public final class CurrentUser {
    public static final String ATTR = "app.user";

    private CurrentUser() {
    }

    /** 由 AuthWebFilter 注入，经过过滤的路由保证非空 */
    public static User of(ServerWebExchange exchange) {
        return (User) exchange.getAttributes().get(ATTR);
    }
}
