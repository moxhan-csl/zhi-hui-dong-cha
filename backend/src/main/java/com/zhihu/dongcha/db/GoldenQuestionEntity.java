package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/**
 * golden_questions：评测题库（取代打包在 jar 里的 golden_set.jsonl）
 * <p>三条读路都要排序（D-2）：按 updatedAt / createdAt 全量倒/正序，以及"只列已复核题"按 updatedAt 倒序，
 * 后者是 (status, updated_at) 复合索引的目标形态。
 */
@Entity
@Table(name = "golden_questions", indexes = {
        @Index(name = "idx_golden_questions_updated", columnList = "updated_at"),
        @Index(name = "idx_golden_questions_created", columnList = "created_at"),
        @Index(name = "idx_golden_questions_status_updated", columnList = "status,updated_at")
})
public class GoldenQuestionEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Lob
    @Column(name = "question", columnDefinition = "TEXT")
    public String question;

    /** 期望答案要点，交给 LLM-as-Judge 做参照 */
    @Lob
    @Column(name = "golden", columnDefinition = "TEXT")
    public String golden;

    /** 期望命中的文档名，检索准确率按它判定 */
    @Column(name = "expected_doc", length = 255)
    public String expectedDoc;

    /** manual | imported */
    @Column(name = "source", length = 20)
    public String source;

    /** draft | reviewed —— 只有 reviewed 的题目参与评测计分 */
    @Column(name = "status", length = 16)
    public String status;

    /** 启用开关：需与 status=reviewed 同时成立才参与评测取题（见 GoldenQuestionService.pickForRun），停用不删题 */
    @Column(name = "enabled")
    public boolean enabled;

    @Lob
    @Column(name = "note", columnDefinition = "TEXT")
    public String note;

    @Column(name = "created_by", length = 120)
    public String createdBy;

    @Column(name = "reviewed_by", length = 120)
    public String reviewedBy;

    @Column(name = "created_at")
    public long createdAt;

    @Column(name = "updated_at")
    public long updatedAt;

    @Column(name = "reviewed_at")
    public long reviewedAt;
}
