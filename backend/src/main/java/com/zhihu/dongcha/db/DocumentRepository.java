package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** documents 的仓库：DbStore 用它做文档读写与对账；D-30 之后这些查询每请求真查 MySQL，无镜像兜底 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    /** 取某库全部文档（含整行），DbStore.docsOfKb 转成 DocRecord 后给库详情/文件列表用 */
    List<DocumentEntity> findByKbId(String kbId);

    /** 全量文档按更新时间倒序，DbStore.allDocs 的读路（跨库列表）；走 updated_at 索引 */
    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();

    /** 某库是否还有文档，删库前的护栏判断（DbStore.hasDocsInKb）；走 kb_id 索引的 EXISTS */
    boolean existsByKbId(String kbId);

    /**
     * 同库内的已有文档名（D-2 业务判重）：只取 name 一列。
     * 上传判重要在落盘之前完成，为此把整库文档实体拉进 JVM 不值当——这条走 kb_id 索引。
     */
    @Query("select e.name from DocumentEntity e where e.kbId = :kbId")
    List<String> namesInKb(@Param("kbId") String kbId);

    /** 启动对账用：上次退出时停在解析中的文档（D-30，替代全表镜像遍历） */
    List<DocumentEntity> findByStatusIn(Collection<String> statuses);

    /**
     * 向量库对账的权威清单：只取 id 一列，不物化整行。
     * 这条查询失败时必须跳过对账，绝不能拿空结果去删向量（D-30）。
     */
    @Query("select e.id from DocumentEntity e")
    List<String> allIds();
}
