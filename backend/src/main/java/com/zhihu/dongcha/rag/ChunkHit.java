package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.model.ChunkRecord;

/** 一次检索命中：chunk + 归一化相似度分（score = 1 - cosine distance） */
public record ChunkHit(ChunkRecord chunk, double score) {
}
