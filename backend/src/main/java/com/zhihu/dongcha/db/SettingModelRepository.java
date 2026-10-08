package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;

/** settings_models 的仓库：只被 DbStore 用，读写都靠继承的 findAll/saveAll（键即主键 k） */
public interface SettingModelRepository extends JpaRepository<SettingModelEntity, String> {
}
