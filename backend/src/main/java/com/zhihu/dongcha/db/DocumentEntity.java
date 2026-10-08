package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/**
 * documents：文档元数据（chunks / 向量在 PostgreSQL pgvector）
 * <p>索引按 {@link DocumentRepository} 里真实存在的查询形态声明（D-2）：按库取文档与判重走 kb_id，
 * 列表排序走 updated_at，启动对账按状态筛。D-30 之后这几条是每请求真查 MySQL，没有镜像兜底。
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_kb_id", columnList = "kb_id"),
        @Index(name = "idx_documents_status", columnList = "status"),
        @Index(name = "idx_documents_updated_at", columnList = "updated_at")
})
public class DocumentEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "name", length = 255)
    public String name;

    @Column(name = "type", length = 20)
    public String type;

    @Column(name = "kb_id", length = 40)
    public String kbId;

    @Column(name = "file_path", length = 500)
    public String filePath;

    @Column(name = "status", length = 20)
    public String status;

    @Column(name = "progress")
    public int progress;

    @Column(name = "chunk_count")
    public int chunkCount;

    @Column(name = "fail_reason", length = 500)
    public String failReason;

    /**
     * 解析阶段（queued/extracting/chunking/embedding/writing/done/failed）。
     * 原先只是内存镜像对象上的运行期字段，D-30 去掉 documents 镜像后必须落库，
     * 否则前端轮询到的进度条就只剩百分比、看不出卡在哪个阶段。
     * 可空列：{@code ddl-auto: update} 只会给新列补 NULL，不碰已有行。
     */
    @Column(name = "stage", length = 20)
    public String stage;

    @Column(name = "created_at")
    public long createdAt;

    @Column(name = "updated_at")
    public long updatedAt;
}
