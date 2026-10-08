package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.config.Availability;
import com.zhihu.dongcha.model.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量库路由：PostgreSQL 可用走 pgvector，否则（含运行期故障）回退内存实现。
 * 降级不是终点：写操作在此排队，后台重探（见 PgStoreRecovery）成功后按序回放到 pg，
 * 保证两侧不因一个降级窗口永久分叉——否则降级期入库的文档切回 pgvector 后就再也查不到。
 */
public class DelegatingChunkStore implements ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(DelegatingChunkStore.class);

    /** 降级窗口内排队待回放的写操作上限；远端长时间不可用时丢弃并告警，不无限吃内存 */
    private static final int PENDING_LIMIT = 2000;

    private final ChunkStore pg;              // 可为 null（app.pg.enabled=false）
    private final MemoryChunkStore memory;
    private final Availability availability;

    private final ConcurrentLinkedDeque<PendingOp> pending = new ConcurrentLinkedDeque<>();
    private final AtomicInteger pendingSize = new AtomicInteger();
    private volatile boolean pendingDropped;

    public DelegatingChunkStore(ChunkStore pg, MemoryChunkStore memory, Availability availability) {
        this.pg = pg;
        this.memory = memory;
        this.availability = availability;
    }

    /** 两个条件同时满足才走 pg：pg 实例存在（app.pg.enabled=false 时为 null）且未被失败标记为不可用 */
    private boolean usePg() {
        return pg != null && availability.pg;
    }

    /** 写路径都是先无条件落内存、再尝试落 pg：pg 出错或正在降级时把这次写排队，等重探回放 */
    @Override
    public void replaceChunks(List<ChunkRecord> chunks) {
        memory.replaceChunks(chunks);
        if (pg == null) return;
        if (usePg()) {
            try {
                pg.replaceChunks(chunks);
            } catch (RuntimeException e) {
                queue(new ReplaceDoc(chunks));
                degrade("replaceChunks", e);
            }
        } else {
            queue(new ReplaceDoc(chunks));
        }
    }

    /** 双删：内存立即清掉，pg 侧失败则排队，否则恢复后 pg 里会留下已删文档的孤儿向量 */
    @Override
    public void deleteByDoc(String docId) {
        memory.deleteByDoc(docId);
        if (pg == null) return;
        if (usePg()) {
            try {
                pg.deleteByDoc(docId);
            } catch (RuntimeException e) {
                queue(new DeleteDoc(docId));
                degrade("deleteByDoc", e);
            }
        } else {
            queue(new DeleteDoc(docId));
        }
    }

    /** 逻辑同 deleteByDoc，只是范围换成整个知识库 */
    @Override
    public void deleteByKb(String kbId) {
        memory.deleteByKb(kbId);
        if (pg == null) return;
        if (usePg()) {
            try {
                pg.deleteByKb(kbId);
            } catch (RuntimeException e) {
                queue(new DeleteKb(kbId));
                degrade("deleteByKb", e);
            }
        } else {
            queue(new DeleteKb(kbId));
        }
    }

    /** 读操作的共同形态：pg 可用先读 pg，抛错则翻降级位并改读内存；读不会排队补写，下面几个同理 */
    @Override
    public long countByDoc(String docId) {
        if (usePg()) {
            try {
                return pg.countByDoc(docId);
            } catch (RuntimeException e) {
                degrade("countByDoc", e);
            }
        }
        return memory.countByDoc(docId);
    }

    @Override
    public long countByKb(String kbId) {
        if (usePg()) {
            try {
                return pg.countByKb(kbId);
            } catch (RuntimeException e) {
                degrade("countByKb", e);
            }
        }
        return memory.countByKb(kbId);
    }

    /** 除监控计数外还是 probePg 的探测语句：能跑通就说明连通性没问题 */
    @Override
    public long countAll() {
        if (usePg()) {
            try {
                return pg.countAll();
            } catch (RuntimeException e) {
                degrade("countAll", e);
            }
        }
        return memory.countAll();
    }

    @Override
    public Map<String, String> distinctDocs() {
        if (usePg()) {
            try {
                return pg.distinctDocs();
            } catch (RuntimeException e) {
                degrade("distinctDocs", e);
            }
        }
        return memory.distinctDocs();
    }

    /** 分块列表页数据源：降级后读的是内存副本，本进程没入库过的文档会直接显示成空列表 */
    @Override
    public List<ChunkRecord> listByDoc(String docId, int offset, int limit) {
        if (usePg()) {
            try {
                return pg.listByDoc(docId, offset, limit);
            } catch (RuntimeException e) {
                degrade("listByDoc", e);
            }
        }
        return memory.listByDoc(docId, offset, limit);
    }

    /** 问答热路径：降级到内存时只比对本进程运行期间写过的 chunk，命中变少但不报错，评测快照据此标 degraded */
    @Override
    public List<ChunkHit> search(List<double[]> queryVectors, Collection<String> kbIds, int topK, double threshold) {
        if (usePg()) {
            try {
                return pg.search(queryVectors, kbIds, topK, threshold);
            } catch (RuntimeException e) {
                degrade("search", e);
            }
        }
        return memory.search(queryVectors, kbIds, topK, threshold);
    }

    /** /health 的 vectorStore 字段取这里，反映此刻真正生效的实现而不是配置意图 */
    @Override
    public String mode() {
        return usePg() ? pg.mode() : memory.mode();
    }

    /** 降级窗口内待回放的写操作数，供健康探针/运维观测 */
    public int pendingOps() {
        return pendingSize.get();
    }

    /**
     * 重探恢复：pg 重新可用时回放降级期排队的写操作，然后把路由切回 pgvector。
     * 由 PgStoreRecovery 在降级期周期性调用。
     */
    public synchronized boolean tryRecover() {
        if (pg == null || availability.pg) return false;
        int replayed;
        try {
            probePg();
            replayed = replayPending();
        } catch (RuntimeException e) {
            log.debug("pgvector 重探失败，继续以内存实现检索（排队写 {} 条）: {}", pendingSize.get(), e.toString());
            return false;
        }
        availability.pg = true;
        if (pendingDropped) {
            log.error("[health] pgvector 已恢复，但降级期间排队写操作曾被丢弃，pg 与内存副本可能分叉——"
                    + "请对相关文档执行重新入库");
            pendingDropped = false;
        }
        log.info("[health] pgvector 重探成功，向量检索已恢复（回放降级期写操作 {} 次）", replayed);
        return true;
    }

    /** 连通性 + schema 幂等检查；失败抛出，由调用方保持降级 */
    private void probePg() {
        if (pg instanceof PgChunkStore p) p.initSchema();
        else pg.countAll();
    }

    /** 先把队列整体排空再逐条回放，返回已回放条数；回放期间新落到的写操作进下一轮 */
    private int replayPending() {
        List<PendingOp> ops = new ArrayList<>();
        PendingOp op;
        while ((op = pending.poll()) != null) ops.add(op);
        pendingSize.set(0);
        int done = 0;
        try {
            for (PendingOp o : ops) {
                o.applyTo(pg);
                done++;
            }
        } catch (RuntimeException e) {
            // 没回放完的原样退回队列，等下一次重探，绝不吞写操作
            for (int i = done; i < ops.size(); i++) pending.offer(ops.get(i));
            pendingSize.addAndGet(ops.size() - done);
            throw e;
        }
        return done;
    }

    /** 超出 PENDING_LIMIT 就不入队，只置 pendingDropped；恢复时据此告警两侧已分叉需重新入库 */
    private void queue(PendingOp op) {
        if (pendingSize.incrementAndGet() > PENDING_LIMIT) {
            pendingSize.decrementAndGet();
            pendingDropped = true;
            return;
        }
        pending.offer(op);
    }

    /** 只在原本可用时翻位并 WARN 一次，降级期间的后续失败不再刷日志 */
    private void degrade(String op, RuntimeException e) {
        if (availability.pg) {
            availability.pg = false;
            log.warn("pgvector {} 失败，向量检索降级为内存实现（后台将自动重探恢复）: {}", op, e.toString());
        }
    }

    /** 降级期写操作；重探成功后按发生顺序回放到 pg */
    private interface PendingOp {
        void applyTo(ChunkStore target);
    }

    private record ReplaceDoc(List<ChunkRecord> chunks) implements PendingOp {
        @Override
        public void applyTo(ChunkStore target) {
            target.replaceChunks(chunks);
        }
    }

    private record DeleteDoc(String docId) implements PendingOp {
        @Override
        public void applyTo(ChunkStore target) {
            target.deleteByDoc(docId);
        }
    }

    private record DeleteKb(String kbId) implements PendingOp {
        @Override
        public void applyTo(ChunkStore target) {
            target.deleteByKb(kbId);
        }
    }
}
