package com.zhihu.dongcha.security;

import com.zhihu.dongcha.common.Json;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.store.Store;
import io.jsonwebtoken.Claims;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局鉴权过滤器：解析 Bearer JWT + 按 API.md 角色矩阵返回 401/403。
 * 角色: EMPLOYEE / KM_ADMIN / SYS_ADMIN（高等级包含低等级读权限，写操作按矩阵收紧）。
 */
@Component
@Order(-100)
public class AuthWebFilter implements WebFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login", "/api/auth/refresh", "/api/health");

    private final JwtService jwt;
    private final Store store;

    public AuthWebFilter(JwtService jwt, Store store) {
        this.jwt = jwt;
        this.store = store;
    }

    /** 免令牌放行 PUBLIC_PATHS（login/refresh/health）、非 /api 路径与 CORS 预检；其余解析 Bearer JWT 后注入用户再过角色矩阵 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();

        // 非 /api 路径（如静态资源、actuator）与 CORS 预检直接放行
        if (!path.startsWith("/api") || HttpMethod.OPTIONS.equals(method) || PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或 Token 缺失");
        }
        Claims claims = jwt.parse(auth.substring(7).trim());
        if (claims == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已过期，请重新登录");
        }
        User user = store.users.get(claims.getSubject());
        if (user == null || !user.enabled) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "账号不存在或已停用");
        }
        exchange.getAttributes().put(CurrentUser.ATTR, user);

        String deny = checkRoleMatrix(path, method, user.role);
        if (deny != null) {
            return reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", deny);
        }
        return chain.filter(exchange);
    }

    /** 返回 null 表示放行，否则返回 403 消息 */
    private String checkRoleMatrix(String path, HttpMethod method, String role) {
        boolean sysAdmin = "SYS_ADMIN".equals(role);
        boolean kmAdmin = sysAdmin || "KM_ADMIN".equals(role);
        boolean mutating = HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.DELETE.equals(method) || HttpMethod.PATCH.equals(method);

        if (path.startsWith("/api/monitor") && !sysAdmin) return "仅系统管理员可访问监控中心";
        if (path.startsWith("/api/settings") && !sysAdmin) return "仅系统管理员可访问系统设置";
        if (path.startsWith("/api/eval") && !kmAdmin) return "需要知识管理员及以上角色";
        if (path.startsWith("/api/knowledge-bases") && mutating && !kmAdmin) return "知识库写操作需要知识管理员及以上角色";
        if (path.startsWith("/api/documents") && mutating && !kmAdmin) return "文档上传/重试/删除需要知识管理员及以上角色";
        return null;
    }

    /** 鉴权失败时直接写出 {code,message} 的 JSON 响应并终止链路，请求不会到达 Controller */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(status);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("code", code, "message", message);
        DataBuffer buffer = resp.bufferFactory()
                .wrap(Json.write(body).getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Mono.just(buffer));
    }
}
