package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/**
 * audit_logs：操作审计
 * <p>这张表只追加、无保留期，是全项目唯一无界增长的表；读侧唯一形态是
 * {@code order by at_time desc limit n}（D-30 删掉镜像后成了唯一读路），所以按 at_time 建索引（D-2）。
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_at", columnList = "at_time")
})
public class AuditLogEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "actor", length = 120)
    public String actor;

    @Column(name = "action", length = 60)
    public String action;

    @Column(name = "target", length = 255)
    public String target;

    @Lob
    @Column(name = "detail", columnDefinition = "TEXT")
    public String detail;

    @Column(name = "at_time")
    public long at;
}
