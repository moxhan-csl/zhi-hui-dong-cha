package com.zhihu.dongcha.common;

import java.util.Locale;

/**
 * 业务唯一性的比较口径（D-2）。
 * <p>共享生产库不加 UNIQUE 约束（业务唯一性与索引是两件事，且约束一旦上去，
 * 存量脏数据会让应用启动或写入直接失败），重名改由写入侧代码拒绝。
 * 拒绝就必须有一个双方都认的口径：首尾空格与大小写不算差别——
 * {@code "制度说明.txt"} 与 {@code " 制度说明.TXT "} 是同一样东西，
 * 而引用卡片只显示名字，留两个"看着一样"的库/文档，用户分不出答案出自哪一个。
 */
public final class Names {

    private Names() {
    }

    /** 比较键：去首尾空白、转小写。返回空串表示"没有名字"，由调用方按必填处理 */
    public static String key(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 展示用形态：只去首尾空白，保留大小写（判重口径不等于存进去的样子） */
    public static String display(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
