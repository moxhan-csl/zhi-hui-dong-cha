package com.zhihu.dongcha.db;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * eval_runs：评测运行（metrics 以 JSON 存放）
 * <p>两条读路（D-2）：列表/趋势按 created_at 倒序，启动对账按 status 筛后再倒序，
 * 所以一个单列索引 + 一个 (status, created_at) 复合索引。
 */
@Entity
@Table(name = "eval_runs", indexes = {
        @Index(name = "idx_eval_runs_created", columnList = "created_at"),
        @Index(name = "idx_eval_runs_status_created", columnList = "status,created_at")
})
public class EvalRunEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "created_at")
    public long createdAt;

    @Column(name = "status", length = 20)
    public String status;

    /** metrics map 序列化的 JSON；由 DbStore.saveEvalRun 写、toEvalRun/toHead 解析回读 */
    @Lob
    @Column(name = "metrics_json", columnDefinition = "TEXT")
    public String metricsJson;

    /** 逐题样本明细的 JSON，LONGTEXT、体积可达 MB 级；列表/趋势读路用投影绕开它 */
    @Lob
    @Column(name = "samples_json", columnDefinition = "LONGTEXT")
    public String samplesJson;

    /** 样本条数冗余列：不解析 samples_json 就能拿到数量 */
    @Column(name = "sample_count")
    public int sampleCount;

    /** 本次实际使用的题目 ID 快照：题集一变，两次运行就不可比 */
    @Lob
    @Column(name = "question_ids", columnDefinition = "TEXT")
    public String questionIds;

    /** 题集指纹（短字符串，最长 64）；与 question_ids 配套，用于判断两次运行的题目是否一致 */
    @Column(name = "question_signature", length = 64)
    public String questionSignature;

    /** 检索环境快照（发起人 / vectorStore / embedMode / RAG 参数），判断两次运行可不可比要看它 */
    @Lob
    @Column(name = "env_json", columnDefinition = "TEXT")
    public String envJson;

    /** 失败原因；DbStore.saveEvalRun 写入前截断到 500，超长会让整条运行记录存不进本列 */
    @Column(name = "fail_reason", length = 500)
    public String failReason;
}
