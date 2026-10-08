package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** golden_questions 的仓库：仅 GoldenQuestionService 直用（不经过 DbStore），管理题与取题都走它 */
public interface GoldenQuestionRepository extends JpaRepository<GoldenQuestionEntity, String> {

    /** 题库管理列表默认按最近更新倒序（全量取，含 question/golden 两块 TEXT） */
    List<GoldenQuestionEntity> findAllByOrderByUpdatedAtDesc();

    /** 按创建时间正序：导出 JSONL 与评测默认取题（pickForRun 未指定 id 时全量取 reviewed+enabled）都用它 */
    List<GoldenQuestionEntity> findAllByOrderByCreatedAtAsc();

    /** 按状态筛后倒序：status="reviewed" 时取可参与评测的题，走 (status, updated_at) 复合索引 */
    List<GoldenQuestionEntity> findByStatusOrderByUpdatedAtDesc(String status);

    /**
     * 已有题干（D-2 手工录入判重）：只取 question 一列，不带 golden / note 两块 TEXT。
     * excludeId 传空串表示不排除任何行（id 是 UUID，永远不会等于空串）。
     */
    @Query("select e.question from GoldenQuestionEntity e where e.id <> :excludeId")
    List<String> questionsOtherThan(@Param("excludeId") String excludeId);
}
