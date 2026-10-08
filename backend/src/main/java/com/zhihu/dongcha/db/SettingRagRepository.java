package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;

/** settings_rag 的仓库：只被 DbStore 用，靠继承的 findAll/saveAll 读写 RAG 参数行（键即主键 k） */
public interface SettingRagRepository extends JpaRepository<SettingRagEntity, String> {
}
