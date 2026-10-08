package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** eval_runs 的仓库：DbStore 用它写评测运行并读出列表/头部，读路都按 created_at 倒序 */
public interface EvalRunRepository extends JpaRepository<EvalRunEntity, String> {

    /** 按实体取全部运行（含 samples_json 整片），DbStore.evalRunsDesc 单条详情/全量还原时用 */
    List<EvalRunEntity> findAllByOrderByCreatedAtDesc();

    /** 启动对账用：上次退出时仍在跑的评测（D-30 / D-18） */
    List<EvalRunEntity> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * 运行"头部"字段，不含 samples_json（D-30）。
     * 趋势图与幻觉率告警只用 metrics，按实体读会把每次运行的样本明细（LONGTEXT，可达 MB 级）
     * 整片拉进 JVM 再丢掉。
     */
    @Query("select e.id, e.createdAt, e.status, e.metricsJson, e.questionIds, e.questionSignature, e.envJson, e.failReason "
            + "from EvalRunEntity e order by e.createdAt desc")
    List<Object[]> runHeadsDesc();
}
