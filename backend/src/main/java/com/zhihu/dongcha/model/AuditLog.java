package com.zhihu.dongcha.model;

import java.util.UUID;

/** 一条操作审计记录：各控制器在改配置/增删用户后 appendAudit 落库，供系统设置页的审计列表读取 */
public class AuditLog {
    public String id = UUID.randomUUID().toString();
    public String actor;   // 操作人账号
    public String action;  // USER_ROLE_CHANGE / KB_CREATE / RAG_CONFIG_UPDATE ...
    public String target;  // 被操作对象标识（配置段名或用户账号等）
    public String detail;  // 变更摘要文本（如 "role=EMPLOYEE → KM_ADMIN"）
    public long at = System.currentTimeMillis();

    public AuditLog() {
    }

    /** 组装一条待落库的审计：四个业务字段由调用方给定，id 与 at 用字段默认值即时生成 */
    public AuditLog(String actor, String action, String target, String detail) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }
}
