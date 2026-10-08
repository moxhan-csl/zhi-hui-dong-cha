package com.zhihu.dongcha.store;

import com.zhihu.dongcha.model.AppConfig;
import com.zhihu.dongcha.model.ChunkRecord;
import com.zhihu.dongcha.model.User;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内运行时状态。D-30 之后职责收敛为三块，且**每一块都不再随业务数据量增长**：
 * 1) 鉴权镜像（users）与运行期配置（config）：users 由 {@link com.zhihu.dongcha.db.DbStore}
 *    启动时载入并按 TTL 重载；知识库/文档/会话/评测**不再做全量镜像**，热点读改直读 MySQL，
 *    内存占用与库里的行数脱钩（权威存储一直是 MySQL）；
 * 2) 向量库内存回退（chunksByDoc/chunksByKb）：仅当 PostgreSQL 不可用时启用；
 * 3) 监控计数器（延迟环形缓冲、分钟请求量、Redis 不可用时的 token 兜底）。
 */
@Component
public class Store {

    public final Map<String, User> users = new ConcurrentHashMap<>();

    public final AppConfig config = new AppConfig();

    /** 向量库回退：docId -> chunks（保序） */
    public final Map<String, List<ChunkRecord>> chunksByDoc = new ConcurrentHashMap<>();
    /** 向量库回退：kbId -> chunks */
    public final Map<String, List<ChunkRecord>> chunksByKb = new ConcurrentHashMap<>();

    /** 延迟环形缓冲（毫秒），最近 2000 条 */
    private final Deque<Long> latencyRing = new ArrayDeque<>();
    private static final int RING_MAX = 2000;

    /** epochMinute -> 请求数 */
    public final Map<Long, AtomicLong> requestBuckets = new ConcurrentHashMap<>();

    /** 记一次请求：延迟入环形缓冲（超出 RING_MAX 从头部丢弃），并按 epoch 分钟号累加该分钟桶的请求数 */
    public synchronized void recordLatency(long ms, long epochMinute) {
        latencyRing.addLast(ms);
        while (latencyRing.size() > RING_MAX) latencyRing.removeFirst();
        requestBuckets.computeIfAbsent(epochMinute, k -> new AtomicLong()).incrementAndGet();
    }

    /** 返回延迟样本的拷贝而非内部队列，MonitorService 拿它算 P95 与样本数 */
    public synchronized List<Long> latenciesSnapshot() {
        return new ArrayList<>(latencyRing);
    }
}
