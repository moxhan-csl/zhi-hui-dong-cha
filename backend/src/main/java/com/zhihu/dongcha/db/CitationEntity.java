package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/**
 * citations：回答的行内引用卡片
 * <p>message_id 由 {@code MessageEntity.citations} 的关联贡献（本实体没有对应字段）；
 * 引用卡片随消息 EAGER 载入并按 idx 排序，所以索引是 (message_id, idx)（D-2）。
 */
@Entity
@Table(name = "citations", indexes = {
        @Index(name = "idx_citations_message_idx", columnList = "message_id,idx")
})
public class CitationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auto_id")
    public Long autoId;

    /** 引用序号，与回答中的 [n] 对齐 */
    @Column(name = "idx")
    public int idx;

    @Column(name = "doc", length = 255)
    public String doc;

    /** 文档业务主键（对应 documents.id），与只作展示用的 doc 名字区分，供前端回链原文 */
    @Column(name = "doc_id", length = 40)
    public String docId;

    @Column(name = "page")
    public int page;

    /** 分块序号（0 起，定位到具体片段）。可空：加列之前落库的旧行没有这个值 */
    @Column(name = "chunk_index")
    public Integer chunkIndex;

    @Lob
    @Column(name = "snippet", columnDefinition = "TEXT")
    public String snippet;

    @Column(name = "score")
    public double score;
}
