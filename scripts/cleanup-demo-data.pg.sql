-- ============================================================================
-- 【2026-09-29 已作废，仅供参考】线上演示数据最终走接口删的（DELETE /api/documents/{id}
--   逐篇 + DELETE /api/knowledge-bases/{id}），本文件里的写操作一段都没有执行过。
--   删库时 chunkStore.deleteByKb 会执行 DELETE FROM chunks WHERE kb_id = ?，
--   所以第 3 段的"24 个孤儿分块"和编造页码（page>0）随四个库一起清掉了；
--   当前 /api/knowledge-bases/overview 返回 totalChunks:0。今后再需要核查，只跑第 1 段。
--   补充：那次删除其实留了尾巴——复查时 documents=0/kbs=0 而 chunks=5（五篇演示语料各 1 块）。
--   2026-09-29 起后端启动会自动对账清掉这类孤儿向量（见 SeedInitializer.reconcileOrphanVectors
--   与 docs/DEPLOY.md 13.3），线上已清到 chunks=0，不需要再手工跑本文件。
-- ----------------------------------------------------------------------------
-- 清理 pgvector 里的种子语料与编造页码
-- 在哪执行：线上那台服务器上（真实地址不写进本仓库，在 /opt/dongcha/.env 里），PowerShell 里跑 psql（或 docker 里的客户端）。
--   psql "host=127.0.0.1 dbname=kbqa user=postgres password=<.env 里的 PG_PASSWORD>" -f 本文件
-- 先跑第 1 段（只读）确认影响面，再决定要不要放开第 3 段的注释。
-- 填错口令/库名：psql 直接报 FATAL，不会误伤；但 -f 跑到第 3 段就是真删，务必先看第 2 段的备份。
-- ============================================================================

-- ---------- 1. 只读：这些数字决定后面删掉的是什么 ----------
SELECT count(*) AS chunks_total,
       count(*) FILTER (WHERE page > 0) AS chunks_with_page_number,
       count(DISTINCT doc_id) AS docs,
       count(DISTINCT kb_id)  AS kbs
FROM chunks;

SELECT kb_id, doc_name, count(*) AS n
FROM chunks GROUP BY kb_id, doc_name ORDER BY kb_id, doc_name;

-- ---------- 2. 备份：这是语料正文的唯一副本，先导出再谈删除 ----------
-- 落在服务器上，拷回本机留存后再删（\copy 是客户端行为，不需要 PG 的文件权限）
\copy (SELECT doc_id, kb_id, doc_name, chunk_index, page, char_count, content FROM chunks ORDER BY kb_id, doc_id, chunk_index) TO 'chunks-backup.csv' WITH (FORMAT csv, HEADER true)

-- ---------- 3. 写操作（默认注释；确认后备份已到手，再逐条放开）----------

-- 3a. 抹掉编造的页码。历史数据里的 page 是入库时用 `i/2+1` 造的，不是从 PDF 抽出来的真实页边界；
--     新版 IngestService 对所有抽取方式都写 0（=未知），前端只在 page>0 时显示页码。
--     执行后"第 N 页"会从分块预览与引用里消失，这是预期效果，不是坏了。
-- UPDATE chunks SET page = 0 WHERE page <> 0;

-- 3b. 删除种子语料的向量。**必须先在 MySQL 侧执行 cleanup-demo-data.mysql.sql 删掉文档与知识库**，
--     否则元数据还在、向量没了，文档页会显示"分块数 N / 预览 0 块"的不一致状态。
--     更稳妥的做法是走接口 DELETE /api/documents/{id}（会同时清 PG 向量、MySQL 元数据和落盘文件）。
-- DELETE FROM chunks WHERE kb_id IN ('<演示库 id，按第 1 段查出的结果填>');

-- 3c. 孤儿分块：pg 里 145 块，但 21 篇文档的分块总数只有 121（corpus-backup.jsonl 逐篇核对过），
--     差的 24 块属于早先删过文档、只删了 MySQL 元数据没删向量的残留。它们不会被检索命中以外的任何界面提到，
--     但会让"总分块数"这个指标虚高。删法：把 doc_id 不在 MySQL documents 表里的行清掉。
--     本文件跨库查不了 MySQL，先单独列出候选（只读），确认后再删：
--     mysql> SELECT id FROM documents;            -- 拿到全部现存文档 id
--     psql>  SELECT doc_id, count(*) FROM chunks GROUP BY doc_id ORDER BY 2 DESC;   -- 两边一对就知道多的是谁
-- DELETE FROM chunks WHERE doc_id NOT IN ('<现存文档 id 列表，逐个填进来>');

-- ---------- 4. 复核 ----------
SELECT count(*) AS chunks_left, count(*) FILTER (WHERE page > 0) AS page_left FROM chunks;
