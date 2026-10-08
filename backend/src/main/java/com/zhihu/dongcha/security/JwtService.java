package com.zhihu.dongcha.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** jjwt HMAC-SHA256：access token 8h，refreshToken 7d */
@Component
public class JwtService {

    private final SecretKey key;
    private static final long ACCESS_TTL_SEC = 8 * 3600;
    private static final long REFRESH_TTL_SEC = 7 * 24 * 3600;

    /** 启动时用 app.jwt.secret 建 HS256 密钥，密钥缺失或不足 32 字节直接抛错拒绝启动（D-29） */
    public JwtService(@Value("${app.jwt.secret}") String secret) {
        // D-29：无兜底密钥。留空或短于 HS256 下限就拒绝启动——带着弱/可预测密钥起来，
        // 等于任何人都能照源码自签令牌，比启动失败严重得多。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret 未配置：JWT_SECRET 必须由环境注入，仓库不提供兜底值");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 不足 32 字节（当前 " + keyBytes.length
                    + "），无法满足 HS256 最小密钥长度");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发业务令牌：subject=userId 并带上 role/name 声明；鉴权时过滤器只取 subject 去内存镜像取用户 */
    public String accessToken(String userId, String role, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ACCESS_TTL_SEC)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 签发刷新令牌：只带 subject 和 type=refresh，不含角色姓名，故不能当 access token 用 */
    public String refreshToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(REFRESH_TTL_SEC)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** access token 有效期秒数，AuthController 用它换算登录响应里的 expiresAt */
    public long accessTtlSeconds() {
        return ACCESS_TTL_SEC;
    }

    /** 解析并校验，非法/过期返回 null */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
