package com.zhihu.dongcha.db;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * audit_logs 的仓库：只有 DbStore 用，写靠 save/appendAudit，读只有一条按时间倒序取页的查询。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {

    /** 按页取，避免把整张审计表拉进内存（D-30） */
    List<AuditLogEntity> findAllByOrderByAtDesc(Pageable pageable);
}
