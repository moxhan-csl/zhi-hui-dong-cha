package com.zhihu.dongcha.model;

import java.util.UUID;

/** 系统用户（POJO）：登录鉴权与系统设置共用，account/empNo 都可作登录标识，role 决定权限 */
public class User {
    public String id = UUID.randomUUID().toString();
    public String name;
    public String account;     // 邮箱
    public String empNo;       // 工号
    public String password;    // BCrypt 密文（存量明文由启动迁移/登录升级）
    public String role;        // EMPLOYEE / KM_ADMIN / SYS_ADMIN
    public String dept;
    public boolean enabled = true;

    public User() {
    }

    /** 建一个用户：id 用默认值生成、enabled 默认 true；此处传入的 password 由调用方决定是否先哈希 */
    public User(String name, String account, String empNo, String password, String role, String dept) {
        this.name = name;
        this.account = account;
        this.empNo = empNo;
        this.password = password;
        this.role = role;
        this.dept = dept;
    }

    /** 登录响应中的 user 视图（不含密码） */
    public java.util.Map<String, Object> view() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("account", account);
        m.put("empNo", empNo);
        m.put("role", role);
        m.put("dept", dept);
        m.put("enabled", enabled);
        return m;
    }
}
