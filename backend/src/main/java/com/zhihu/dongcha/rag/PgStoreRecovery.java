package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.config.Availability;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 向量库降级自愈：pgvector 一旦因瞬时故障被标记不可用，Availability.pg 会一直保持 false，
 * 请求路径因此永久落在内存副本上（历史语料并不在内存里，表现为"全库查不到"）。
 * 这里在降级期周期性重探，恢复由 DelegatingChunkStore 负责回放排队写并切回 pgvector。
 * 正常态不做任何事，探测也不占用请求线程。
 */
@Component
public class PgStoreRecovery {

    private final DelegatingChunkStore chunkStore;
    private final Availability availability;

    public PgStoreRecovery(DelegatingChunkStore chunkStore, Availability availability) {
        this.chunkStore = chunkStore;
        this.availability = availability;
    }

    /** initialDelay 与 fixedDelay 同取 app.pg.reprobe-interval-ms：启动后首次探测也要等满一个周期 */
    @Scheduled(initialDelayString = "${app.pg.reprobe-interval-ms:30000}",
            fixedDelayString = "${app.pg.reprobe-interval-ms:30000}")
    public void reprobe() {
        if (!availability.pg) chunkStore.tryRecover();
    }
}
