package com.zhihu.dongcha.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码摘要存储：users.password 列存 BCrypt 密文（60 字符，列宽 120 兼容）。
 * 存量明文通过 {@link #verify} 兼容比对，由登录/启动迁移路径透明升级。
 */
public final class Passwords {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private Passwords() {
    }

    /** 生成 BCrypt 密文；raw 为 null 时按空串参与摘要，不让 encode 抛异常 */
    public static String hash(String raw) {
        return ENCODER.encode(raw == null ? "" : raw);
    }

    /** 以 $2 前缀识别存的是 BCrypt 密文还是未迁移的明文，登录成功后据此决定是否就地升级 */
    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith("$2");
    }

    /** 密文走 BCrypt 比对；未迁移的明文走等价比对（调用方成功后应升级存哈希） */
    public static boolean verify(String raw, String stored) {
        if (raw == null || stored == null) return false;
        return isHashed(stored) ? ENCODER.matches(raw, stored) : stored.equals(raw);
    }
}
