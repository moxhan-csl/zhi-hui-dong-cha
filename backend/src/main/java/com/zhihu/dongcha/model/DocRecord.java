package com.zhihu.dongcha.model;

import java.util.UUID;

/** 一份已上传文档的入库记录（POJO）：跟踪解析状态与进度，分块正文另存 pgvector/内存 */
public class DocRecord {
    public String id = UUID.randomUUID().toString();
    public String name;
    public String type;          // pdf/docx/xlsx/txt/md
    public String kbId;
    public String filePath;      // data/uploads 下路径
    public String status = "PENDING"; // PENDING / PARSING / READY / FAILED
    public int progress = 0;           // 0-100，口径见 IngestService.process
    public int chunkCount = 0;         // 入库分块数（向量正文在 PG / 内存回退）
    public String failReason;
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    /** 当前解析阶段（extracting/chunking/embedding/writing/done/failed），落 documents.stage 列 */
    public volatile String stage;

    public DocRecord() {
    }
}
