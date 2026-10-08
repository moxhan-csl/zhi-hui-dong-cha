package com.zhihu.dongcha.model;

/** 文档切分出的一个向量分块（POJO）：docId/kbId 关联归属，vector 为其 embedding */
public class ChunkRecord {
    public String id;
    public String docId;
    public String docName;
    public String kbId;
    public int index;
    public String text;
    public int charCount;
    /** 页码，1 起；0 = 未知（txt/md/docx/xlsx 没有页边界概念，PDF 逐页失败也落这里）。不是"第 0 页" */
    public int page;
    /** 分块的向量表示；入库正文在 pgvector，内存回退实现时随本字段持有 */
    public double[] vector;
    /** 检索命中分（仅检索结果使用） */
    public double score;

    public ChunkRecord() {
    }
}
