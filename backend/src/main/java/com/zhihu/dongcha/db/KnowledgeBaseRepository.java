package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** knowledge_bases 的仓库：DbStore 用它取库清单与判重；成员随实体 EAGER 载入（见实体注解） */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {

    /** 全部知识库按创建时间正序（DbStore.allKbs 的唯一读路，小表全量扫，无二级索引可用） */
    List<KnowledgeBaseEntity> findAllByOrderByCreatedAtAsc();

    /** 其它库的名字（D-2 业务判重）：只取一列，建库/改名时用来拒绝重名 */
    @Query("select e.name from KnowledgeBaseEntity e where e.id <> :excludeId")
    List<String> namesOtherThan(@Param("excludeId") String excludeId);
}
