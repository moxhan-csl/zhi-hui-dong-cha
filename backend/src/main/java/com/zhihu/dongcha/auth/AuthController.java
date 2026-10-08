package com.zhihu.dongcha.auth;

import com.zhihu.dongcha.common.BizException;
import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.User;
import com.zhihu.dongcha.security.CurrentUser;
import com.zhihu.dongcha.security.JwtService;
import com.zhihu.dongcha.security.Passwords;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 认证接口。登录账号=邮箱或工号(≤50)，密码 6-32 位；
 * 失败统一 401 {message:"账号或密码不正确"}；连败 5 次 → 429 冷却 60s（计数存 Redis）。
 * <p>用户校验读 MySQL（{@link DbStore#userByAccount}），Redis/JPA 均为阻塞调用，
 * 统一在 boundedElastic 线程上执行，不占用 event-loop。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9_-]{4,50}$");

    private final DbStore db;
    private final JwtService jwt;
    private final LoginAttemptService attempts;

    public AuthController(DbStore db, JwtService jwt, LoginAttemptService attempts) {
        this.db = db;
        this.jwt = jwt;
        this.attempts = attempts;
    }

    public record LoginRequest(String account, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    /** 登录入口：仅把 doLogin 的阻塞流程挪到 boundedElastic，账号未命中与密码错误返回同一个 401 文案 */
    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody(required = false) LoginRequest req) {
        return Mono.fromCallable(() -> doLogin(req)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 登录实体：格式校验 400→连败冷却 429→查库验密 401→存量明文就地升级 BCrypt→签发令牌对 */
    private Map<String, Object> doLogin(LoginRequest req) {
        if (req == null || req.account() == null || req.password() == null) {
            throw BizException.badRequest("账号与密码不能为空");
        }
        String account = req.account().trim();
        if (account.length() > 50 || !(EMAIL.matcher(account).matches() || EMP_NO.matcher(account).matches())) {
            throw BizException.badRequest("账号需为邮箱或员工工号（≤50 字符）");
        }
        if (req.password().length() < 6 || req.password().length() > 32) {
            throw BizException.badRequest("密码需为 6-32 位");
        }

        long cooldown = attempts.remainingCooldown(account);
        if (cooldown > 0) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "登录失败次数过多，请 60 秒后重试", (int) Math.max(cooldown, 1));
        }

        Optional<User> user = db.userByAccount(account);
        if (user.isEmpty() || !user.get().enabled || !Passwords.verify(req.password(), user.get().password)) {
            attempts.recordFailure(account);
            throw new BizException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号或密码不正确");
        }
        User u = user.get();
        if (!Passwords.isHashed(u.password)) {
            // 存量明文：登录成功即透明升级为 BCrypt 密文
            u.password = Passwords.hash(req.password());
            db.saveUser(u);
        }
        attempts.reset(account);
        return tokenPair(u);
    }

    /** 刷新令牌：只认 claims 里 type=refresh 的那枚（access 不能用来换发），且用户须仍在库且 enabled */
    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        return Mono.fromCallable(() -> {
            if (req == null || req.refreshToken() == null) throw BizException.badRequest("refreshToken 不能为空");
            Claims claims = jwt.parse(req.refreshToken());
            if (claims == null || !"refresh".equals(claims.get("type"))) {
                throw new BizException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "刷新令牌无效或已过期");
            }
            User user = db.userById(claims.getSubject())
                    .filter(u -> u.enabled)
                    .orElseThrow(() -> new BizException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "用户不存在或已停用"));
            return tokenPair(user);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 当前用户信息：直接读 AuthWebFilter 已注入 exchange 的用户，不再查库 */
    @GetMapping("/me")
    public Mono<Map<String, Object>> me(ServerWebExchange ex) {
        return Mono.just(CurrentUser.of(ex).view());
    }

    /** SSO 回调端点已移除：它不校验 code，任意非空值即可换走演示账号的 token，等于免密登录入口。 */
    private Map<String, Object> tokenPair(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwt.accessToken(user.id, user.role, user.name));
        m.put("refreshToken", jwt.refreshToken(user.id));
        m.put("expiresAt", Instant.now().plusSeconds(jwt.accessTtlSeconds()).getEpochSecond());
        m.put("user", user.view());
        return m;
    }
}
