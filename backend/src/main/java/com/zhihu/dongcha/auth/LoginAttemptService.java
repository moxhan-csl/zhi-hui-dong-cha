package com.zhihu.dongcha.auth;

import com.zhihu.dongcha.redis.RedisOps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败冷却：Redis 计数 key {@code login:fail:{account}}，TTL 60s；
 * 累计 5 次即进入冷却（剩余时间取 key TTL）。Redis 不可用时由 RedisOps 内存兜底，行为一致。
 */
@Component
public class LoginAttemptService {

    public static final int MAX_FAIL = 5;
    public static final long COOLDOWN_SEC = 60;

    private final RedisOps redis;
    private final int maxFail;
    private final long cooldownSec;

    public LoginAttemptService(RedisOps redis,
                               @Value("${app.redis.login-fail-max}") int maxFail,
                               @Value("${app.redis.login-fail-ttl-seconds}") long cooldownSec) {
        this.redis = redis;
        this.maxFail = maxFail;
        this.cooldownSec = cooldownSec;
    }

    /** 失败计数 key：login:fail:{小写账号}，账号统一转小写，避免大小写变体各开一份计数 */
    private String key(String account) {
        return "login:fail:" + account.toLowerCase();
    }

    /** 冷却剩余秒数，0 表示未锁定 */
    public long remainingCooldown(String account) {
        long fails = parse(redis.blockingGet(key(account)));
        if (fails < maxFail) return 0;
        long ttl = redis.blockingTtl(key(account));
        return ttl <= 0 ? 0 : ttl;
    }

    /** 记一次失败并续 TTL；刚好达阈值时再 incr 0 一次，把冷却窗口从这次失败重新对齐计时 */
    public void recordFailure(String account) {
        long fails = redis.blockingIncr(key(account), 1, Duration.ofSeconds(cooldownSec));
        if (fails >= maxFail) {
            // 达到阈值：重新对齐 60s 冷却窗口
            redis.blockingIncr(key(account), 0, Duration.ofSeconds(cooldownSec));
        }
    }

    /** 登录成功后删掉计数 key，解除该账号的冷却 */
    public void reset(String account) {
        redis.blockingDel(key(account));
    }

    /** 原始失败计数，未做阈值折算：锁定前也能读到中间值（remainingCooldown 只在达标后才给秒数） */
    public long failures(String account) {
        return parse(redis.blockingGet(key(account)));
    }

    /** Redis 里存的是浮点串，先按 double 解析再取整；缺失或脏数据一律当 0 次，不让登录被坏计数卡死 */
    private long parse(String v) {
        try {
            return v == null ? 0 : (long) Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }
}
