package com.zhihu.dongcha.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 评测运行记录 */
public class EvalRun {
    public String id = UUID.randomUUID().toString();
    public long createdAt = System.currentTimeMillis();
    public volatile String status = "running"; // running / done / failed
    /** 各项评测指标（指标名→分值）；运行中即被 view() 读取，故底层用 ConcurrentHashMap */
    public volatile Map<String, Double> metrics = new ConcurrentHashMap<>();
    /** 每题样本明细（含 scores 等），仅 view(withSamples=true) 时整体输出 */
    public final List<Map<String, Object>> samples = new ArrayList<>();
    /** 本次实际参与评测的题目，题集指纹变了就说明趋势不可比 */
    public final List<String> questionIds = new ArrayList<>();
    /** 题集指纹串：签名不同的两次运行分数不可横向比较（配合 env 快照判断可比性） */
    public volatile String questionSignature;
    /**
     * 本次运行的检索环境快照（发起人、向量库实现、embedding 实现、RAG 参数）。
     * 分数的可比性不只取决于题集：memory 检索与 pgvector 检索、不同发起人可见范围下跑出的
     * 同一套题不是同一个测量，没有这份快照就没人能事后判断"能不能比"。
     */
    public volatile Map<String, Object> env = new LinkedHashMap<>();
    /** 失败原因（仅 failed 状态有；字段口径为 null 时序列化直接缺席） */
    public volatile String failReason;

    /** 输出前端视图：id/状态/指标/env 快照/failReason/题量/scores（scores 取各样本的 scores 字段）；withSamples 为真才附完整 samples */
    public Map<String, Object> view(boolean withSamples) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("createdAt", createdAt);
        m.put("status", status);
        m.put("metrics", metrics);
        m.put("questionSignature", questionSignature);
        synchronized (env) {
            m.put("env", new LinkedHashMap<>(env));
        }
        m.put("failReason", failReason);
        synchronized (questionIds) {
            m.put("questionCount", questionIds.size());
        }
        synchronized (samples) {
            m.put("scores", samples.stream().map(s -> s.get("scores")).toList());
            if (withSamples) m.put("samples", new ArrayList<>(samples));
        }
        return m;
    }
}
